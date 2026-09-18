/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.java

import org.jetbrains.lincheck.jvm.agent.ClassModel
import org.jetbrains.lincheck.jvm.agent.ConstructorModel
import org.jetbrains.lincheck.jvm.agent.MethodModel
import org.jetbrains.lincheck.jvm.agent.NestedClassModel
import org.jetbrains.lincheck.jvm.agent.loadClassModel
import org.jetbrains.lincheck.jvm.agent.expressions.CapturedLocal
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionKind
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionWrapper
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionBytecodeRewriter
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionCompilationException
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionCompiler
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionEvaluatorTransplanter
import org.jetbrains.lincheck.jvm.agent.expressions.expressionClassNames
import org.jetbrains.lincheck.jvm.agent.expressions.expressionClasspath
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import org.objectweb.asm.Type
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier
import javax.tools.JavaCompiler
import javax.tools.ToolProvider

/**
 * Compiles a Java expression in a disposable source copy of its enclosing application class.
 *
 * This is the language-specific middle of the [ExpressionCompiler] pipeline.
 * It reads class-file models without loading the application classes, renders their fields and method signatures,
 * and compiles the expression where Java resolves `this`, implicit members, inheritance, and overloads correctly.
 * It then asks [ExpressionEvaluatorTransplanter] to move the evaluator into the wrapper and
 * [ExpressionBytecodeRewriter] to replace accesses to mocked non-public members.
 *
 * Given a `Counter` with private field `count`, local `limit`, and condition `count > limit`, the input to this phase
 * is the expression, capture types, `Counter`'s class model, and the wrapper source.
 * Its temporary source has this shape:
 * ```java
 * public class Counter {
 *     private int count;
 *     private boolean __evaluate(int limit) { return count > limit; }
 * }
 * ```
 * The temporary `Counter` is discarded; the output is the wrapper class files containing that evaluator.
 */
internal object JavaEnclosingClassEvaluator {
    private const val EVALUATE_METHOD = "__evaluate"

    /**
     * The facade uses the application's binary name, so `this`, implicit members, overload resolution, and emitted
     * owners match the real class. Only its declaration surface is reproduced; the facade bytecode is discarded.
     * The extracted evaluator is moved to the wrapper as a static method whose first argument replaces the original
     * receiver slot.
     *
     * Superclass facades preserve inherited member lookup. Nested-class lexical receivers, `super`, and
     * compiler-generated evaluator helpers require relocating more of the original class context and are rejected or
     * fail with a compilation diagnostic. Receiver-independent expressions in nested classes remain supported.
     */
    fun compile(
        wrapperBinaryName: String,
        wrapperSource: String,
        expressions: List<String>,
        kind: ExpressionKind,
        captures: List<CapturedLocal>,
        enclosing: ClassModel,
        receiverCapture: CapturedLocal?,
        classLoader: ClassLoader?,
        systemCompiler: JavaCompiler? = ToolProvider.getSystemJavaCompiler(),
    ): Map<String, ByteArray> {
        require(receiverCapture == null || '$' !in enclosing.binaryName) {
            "Agent-side Java expressions using an enclosing receiver are not yet supported in nested classes"
        }
        require(!enclosing.isInterface) {
            "Agent-side Java expressions using an enclosing receiver are not yet supported in interfaces"
        }

        val evaluationCaptures = captures.filterNot { it.name == ExpressionWrapper.INSTANCE_FIELD }
        val hierarchy = discoverHierarchy(enclosing, classLoader)
        val nestedClasses = enclosing.nestedClasses.mapNotNull { nested ->
            loadClassModel(nested.binaryName, classLoader)?.let { nested to it }
        }
        val hierarchyNames = hierarchy.mapTo(HashSet(), ClassModel::binaryName)
        val facadeSources = hierarchy.map { model ->
            JavaSource(
                model.binaryName,
                renderFacade(
                    model,
                    expressions.takeIf { model === enclosing },
                    kind,
                    evaluationCaptures,
                    receiverCapture != null,
                    model.superclassBinaryName?.takeIf { it in hierarchyNames },
                    nestedClasses.takeIf { model === enclosing }.orEmpty(),
                ),
            )
        }
        val requiredClassNames = expressionClassNames(
            hierarchy + nestedClasses.map { it.second },
            captures.map { it.type },
        )
        // The facade and the wrapper resolve the same application types, so they share one prepared classpath.
        return expressionClasspath(classLoader, requiredClassNames).use { classpath ->
            val facadeCompilation = JavaExpressionToolchain.compile(facadeSources, classpath, systemCompiler)
            val facadeBytes = facadeCompilation.classes[enclosing.binaryName]
                ?: throw ExpressionCompilationException("Expression compiler produced no enclosing-class facade")

            val wrapperCompilation = JavaExpressionToolchain.compile(
                listOf(JavaSource(wrapperBinaryName, wrapperSource)), classpath, systemCompiler,
            )
            val wrapperBytes = wrapperCompilation.classes[wrapperBinaryName]
                ?: throw ExpressionCompilationException("Expression compiler produced no wrapper class")

            val transplanted = ExpressionEvaluatorTransplanter.transplant(
                facadeBytes = facadeBytes,
                wrapperBytes = wrapperBytes,
                evaluatorMethodName = EVALUATE_METHOD,
                languageName = "Java",
                evaluatorReceiverType = receiverCapture?.let {
                    Type.getObjectType(enclosing.binaryName.replace('.', '/'))
                },
                receiverCapture = receiverCapture,
                evaluationCaptures = evaluationCaptures,
                kind = kind,
            )
            wrapperCompilation.mockedMembers.addAll(facadeCompilation.mockedMembers)
            wrapperCompilation.mockedMembers.addNonPublicMembers(hierarchy + nestedClasses.map { it.second })
            val classes = wrapperCompilation.classes.toMutableMap().apply { put(wrapperBinaryName, transplanted) }
            ExpressionBytecodeRewriter.rewrite(classes, wrapperCompilation.mockedMembers)
        }
    }

    private fun renderFacade(
        enclosing: ClassModel,
        expressions: List<String>?,
        kind: ExpressionKind,
        captures: List<CapturedLocal>,
        hasReceiver: Boolean,
        superclassBinaryName: String?,
        nestedClasses: List<Pair<NestedClassModel, ClassModel>>,
    ): String {
        val packageName = enclosing.binaryName.substringBeforeLast('.', "")
        val simpleName = enclosing.binaryName.substringAfterLast('.')
        val facade = TypeSpec.classBuilder(simpleName).addModifiers(Modifier.PUBLIC)
        superclassBinaryName?.let { facade.superclass(it.javaClassName()) }
        enclosing.declaredFields.values.forEach { field ->
            val modifiers = buildList {
                when {
                    field.isPublic -> add(Modifier.PUBLIC)
                    field.isProtected -> add(Modifier.PROTECTED)
                    field.isPrivate -> add(Modifier.PRIVATE)
                }
                if (field.isStatic) add(Modifier.STATIC)
            }
            facade.addField(
                FieldSpec.builder(field.type.javaTypeName(), field.name, *modifiers.toTypedArray()).build(),
            )
        }
        enclosing.declaredMethods
            .asSequence()
            .filterNot(MethodModel::isSynthetic)
            .filter { SourceVersion.isIdentifier(it.name) && !SourceVersion.isKeyword(it.name) }
            .distinctBy { it.name to Type.getArgumentTypes(it.descriptor).joinToString { type -> type.descriptor } }
            .map(::methodStub)
            .forEach(facade::addMethod)
        nestedClasses.map(::nestedClassStub).forEach(facade::addType)

        if (expressions == null) return JavaFile.builder(packageName, facade.build()).build().toString()

        val evaluator = MethodSpec.methodBuilder(EVALUATE_METHOD)
            .addModifiers(Modifier.PRIVATE)
            .apply { if (!hasReceiver) addModifiers(Modifier.STATIC) }
            .addException(Throwable::class.java)
        captures.forEachIndexed { index, capture ->
            evaluator.addParameter(capture.type.javaTypeName(), capture.name.ifEmpty { "arg$index" })
        }
        when (kind) {
            ExpressionKind.CONDITION -> evaluator
                .returns(TypeName.BOOLEAN)
                .addStatement("return (\$L)", expressions.single())
            ExpressionKind.WATCHES -> {
                val values = CodeBlock.builder()
                expressions.forEachIndexed { index, expression ->
                    if (index > 0) values.add(", ")
                    values.add("(\$L)", expression)
                }
                evaluator
                    .returns(ArrayTypeName.of(TypeName.OBJECT))
                    .addStatement("return new \$T[] { \$L }", TypeName.OBJECT, values.build())
            }
        }
        facade.addMethod(evaluator.build())
        return JavaFile.builder(packageName, facade.build()).build().toString()
    }

    private fun methodStub(method: MethodModel): MethodSpec {
        val methodType = Type.getMethodType(method.descriptor)
        val builder = MethodSpec.methodBuilder(method.name)
            .returns(methodType.returnType.javaTypeName())
        when {
            method.isPublic -> builder.addModifiers(Modifier.PUBLIC)
            method.isProtected -> builder.addModifiers(Modifier.PROTECTED)
            method.isPrivate -> builder.addModifiers(Modifier.PRIVATE)
        }
        if (method.isStatic) builder.addModifiers(Modifier.STATIC)
        methodType.argumentTypes.forEachIndexed { index, type ->
            builder.addParameter(type.javaTypeName(), "p$index")
        }
        if (method.isVarArgs) builder.varargs(true)
        if (methodType.returnType.sort != Type.VOID) {
            builder.addStatement("return \$L", methodType.returnType.defaultJavaValue())
        }
        return builder.build()
    }

    private fun nestedClassStub(nested: Pair<NestedClassModel, ClassModel>): TypeSpec {
        val (declaration, model) = nested
        val builder = TypeSpec.classBuilder(declaration.simpleName)
        val modifiers = buildList {
            when {
                declaration.isPublic -> add(Modifier.PUBLIC)
                declaration.isProtected -> add(Modifier.PROTECTED)
                declaration.isPrivate -> add(Modifier.PRIVATE)
            }
            if (declaration.isStatic) add(Modifier.STATIC)
        }
        builder.addModifiers(*modifiers.toTypedArray())
        model.declaredFields.values.forEach { field ->
            val fieldModifiers = buildList {
                when {
                    field.isPublic -> add(Modifier.PUBLIC)
                    field.isProtected -> add(Modifier.PROTECTED)
                    field.isPrivate -> add(Modifier.PRIVATE)
                }
                if (field.isStatic) add(Modifier.STATIC)
            }
            builder.addField(
                FieldSpec.builder(field.type.javaTypeName(), field.name, *fieldModifiers.toTypedArray()).build(),
            )
        }
        model.declaredMethods.asSequence()
            .filterNot(MethodModel::isSynthetic)
            .filter { SourceVersion.isIdentifier(it.name) && !SourceVersion.isKeyword(it.name) }
            .map(::methodStub)
            .forEach(builder::addMethod)
        model.declaredConstructors
            .map { constructor -> constructorStub(constructor, hasEnclosingInstance = !declaration.isStatic) }
            .forEach(builder::addMethod)
        return builder.build()
    }

    private fun constructorStub(constructor: ConstructorModel, hasEnclosingInstance: Boolean): MethodSpec {
        val builder = MethodSpec.constructorBuilder()
        when {
            constructor.isPublic -> builder.addModifiers(Modifier.PUBLIC)
            constructor.isProtected -> builder.addModifiers(Modifier.PROTECTED)
            constructor.isPrivate -> builder.addModifiers(Modifier.PRIVATE)
        }
        Type.getArgumentTypes(constructor.descriptor)
            .drop(if (hasEnclosingInstance) 1 else 0)
            .forEachIndexed { index, type ->
                builder.addParameter(type.javaTypeName(), "p$index")
            }
        return builder.build()
    }

    private fun discoverHierarchy(enclosing: ClassModel, classLoader: ClassLoader?): List<ClassModel> = buildList {
        var current: ClassModel? = enclosing
        while (current != null) {
            val model = current
            if (any { it.binaryName == model.binaryName }) break
            add(model)
            current = model.superclassBinaryName
                ?.takeUnless(String::isPlatformType)
                ?.let { loadClassModel(it, classLoader) }
        }
    }

}

private fun String.javaClassName(): ClassName {
    val packageName = substringBeforeLast('.', "")
    return ClassName.get(packageName, substringAfterLast('.'))
}

private fun String.isPlatformType(): Boolean =
    startsWith("java.") || startsWith("javax.") || startsWith("jdk.") || startsWith("sun.")

private fun Type.javaTypeName(): TypeName = when (sort) {
    Type.VOID -> TypeName.VOID
    Type.BOOLEAN -> TypeName.BOOLEAN
    Type.CHAR -> TypeName.CHAR
    Type.BYTE -> TypeName.BYTE
    Type.SHORT -> TypeName.SHORT
    Type.INT -> TypeName.INT
    Type.FLOAT -> TypeName.FLOAT
    Type.LONG -> TypeName.LONG
    Type.DOUBLE -> TypeName.DOUBLE
    Type.ARRAY -> ArrayTypeName.of(Type.getType(descriptor.substring(1)).javaTypeName())
    else -> {
        val packageName = className.substringBeforeLast('.', "")
        val simpleNames = className.substringAfterLast('.').split('$')
        ClassName.get(packageName, simpleNames.first(), *simpleNames.drop(1).toTypedArray())
    }
}

private fun Type.defaultJavaValue(): CodeBlock = CodeBlock.of(when (sort) {
    Type.BOOLEAN -> "false"
    Type.CHAR -> "'\\u0000'"
    Type.BYTE, Type.SHORT, Type.INT -> "0"
    Type.FLOAT -> "0.0f"
    Type.LONG -> "0L"
    Type.DOUBLE -> "0.0"
    else -> "null"
})
