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
import org.jetbrains.lincheck.jvm.agent.FieldModel
import org.jetbrains.lincheck.jvm.agent.MethodModel
import org.jetbrains.lincheck.jvm.agent.NestedClassModel
import org.jetbrains.lincheck.jvm.agent.loadClassModel
import org.jetbrains.lincheck.jvm.agent.expressions.ApplicationClasses
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
     * A nested enclosing class is a top-level facade named by its binary simple name (`Outer$Inner` is a legal
     * Java identifier), so its own members resolve; the lexical scope of its outer class does not.
     * Superclass facades preserve inherited member lookup, with every member visible the way the debugger's
     * reflective access sees it. `super` and compiler-generated evaluator helpers require relocating more of the
     * original class context and are rejected or fail with a compilation diagnostic.
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
        require(!enclosing.isInterface) {
            "Agent-side Java expressions using an enclosing receiver are not yet supported in interfaces"
        }

        val evaluationCaptures = captures.filterNot { it.name == ExpressionWrapper.INSTANCE_FIELD }
        val hierarchy = discoverHierarchy(enclosing, classLoader)
        val nestedClasses = enclosing.nestedClasses.mapNotNull { nested ->
            loadClassModel(nested.binaryName, classLoader)?.let { nested to it }
        }
        val hierarchyNames = hierarchy.mapTo(HashSet(), ClassModel::binaryName)
        val facadeSources = hierarchy.mapIndexed { index, model ->
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
                    hierarchyNames,
                    redeclaredBelow = hierarchy.take(index).flatMap(ClassModel::declaredMethods).mapTo(HashSet(), MethodModel::signatureKey),
                    classLoader,
                ),
            )
        }
        val applicationClasses = ApplicationClasses(classLoader, hierarchy + nestedClasses.map { it.second })
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
                applicationClasses = applicationClasses,
            )
            wrapperCompilation.mockedMembers.addAll(facadeCompilation.mockedMembers)
            wrapperCompilation.mockedMembers.addNonPublicMembers(hierarchy + nestedClasses.map { it.second })
            val classes = wrapperCompilation.classes.toMutableMap().apply { put(wrapperBinaryName, transplanted) }
            ExpressionBytecodeRewriter.rewrite(classes, wrapperCompilation.mockedMembers, applicationClasses)
        }
    }

    /**
     * Renders one facade of the hierarchy; [expressions] is non-null for the enclosing class, which gets the
     * evaluator. A superclass facade shows the subclass every member, so a private one becomes `protected`:
     * the expression then reaches it the way the debugger's reflective access does, and the rewriter routes
     * the compiled access through reflection. A method a descendant redeclares ([redeclaredBelow]) is left out,
     * since widening it would turn the descendant's declaration into an illegal override; the descendant's
     * declaration is what the expression resolves to anyway.
     */
    private fun renderFacade(
        model: ClassModel,
        expressions: List<String>?,
        kind: ExpressionKind,
        captures: List<CapturedLocal>,
        hasReceiver: Boolean,
        superclassBinaryName: String?,
        nestedClasses: List<Pair<NestedClassModel, ClassModel>>,
        facadeNames: Set<String>,
        redeclaredBelow: Set<MethodSignatureKey>,
        classLoader: ClassLoader?,
    ): String {
        val packageName = model.binaryName.substringBeforeLast('.', "")
        val simpleName = model.binaryName.substringAfterLast('.')
        val widenToSubclasses = expressions == null
        val facade = TypeSpec.classBuilder(simpleName).addModifiers(Modifier.PUBLIC)
        superclassBinaryName?.let { facade.superclass(it.javaClassName(facadeNames)) }
        model.declaredFields.values.filterNot(FieldModel::isSynthetic).forEach { field ->
            val modifiers = memberModifiers(
                field.isPublic, field.isProtected, field.isPrivate, field.isStatic, widenToSubclasses,
            )
            facade.addField(FieldSpec.builder(field.type.javaTypeName(facadeNames), field.name, *modifiers).build())
        }
        model.declaredMethods
            .asSequence()
            .filterNot(MethodModel::isSynthetic)
            .filter { SourceVersion.isIdentifier(it.name) && !SourceVersion.isKeyword(it.name) }
            .filter { it.signatureKey() !in redeclaredBelow }
            .distinctBy(MethodModel::signatureKey)
            .map { methodStub(it, facadeNames, widenToSubclasses) }
            .forEach(facade::addMethod)
        nestedClasses.map { nestedClassStub(it, nestedClasses, facadeNames, classLoader) }.forEach(facade::addType)

        if (expressions == null) return JavaFile.builder(packageName, facade.build()).build().toString()

        val evaluator = MethodSpec.methodBuilder(EVALUATE_METHOD)
            .addModifiers(Modifier.PRIVATE)
            .apply { if (!hasReceiver) addModifiers(Modifier.STATIC) }
            .addException(Throwable::class.java)
        captures.forEachIndexed { index, capture ->
            evaluator.addParameter(capture.type.javaTypeName(facadeNames), capture.name.ifEmpty { "arg$index" })
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

    private fun memberModifiers(
        isPublic: Boolean,
        isProtected: Boolean,
        isPrivate: Boolean,
        isStatic: Boolean,
        widenToSubclasses: Boolean,
    ): Array<Modifier> = buildList {
        when {
            isPublic -> add(Modifier.PUBLIC)
            isProtected || widenToSubclasses -> add(Modifier.PROTECTED)
            isPrivate -> add(Modifier.PRIVATE)
        }
        if (isStatic) add(Modifier.STATIC)
    }.toTypedArray()

    private fun methodStub(method: MethodModel, facadeNames: Set<String>, widenToSubclasses: Boolean = false): MethodSpec {
        val methodType = Type.getMethodType(method.descriptor)
        val builder = MethodSpec.methodBuilder(method.name)
            .returns(methodType.returnType.javaTypeName(facadeNames))
            .addModifiers(*memberModifiers(method.isPublic, method.isProtected, method.isPrivate, method.isStatic, widenToSubclasses))
        methodType.argumentTypes.forEachIndexed { index, type ->
            builder.addParameter(type.javaTypeName(facadeNames), "p$index")
        }
        if (method.isVarArgs) builder.varargs(true)
        if (methodType.returnType.sort != Type.VOID) {
            builder.addStatement("return \$L", methodType.returnType.defaultJavaValue())
        }
        return builder.build()
    }

    private fun nestedClassStub(
        nested: Pair<NestedClassModel, ClassModel>,
        siblings: List<Pair<NestedClassModel, ClassModel>>,
        facadeNames: Set<String>,
        classLoader: ClassLoader?,
    ): TypeSpec {
        val (declaration, model) = nested
        val builder = TypeSpec.classBuilder(declaration.simpleName)
        builder.addModifiers(
            *memberModifiers(
                declaration.isPublic, declaration.isProtected, declaration.isPrivate, declaration.isStatic, false,
            ),
        )
        val superclass = model.superclassBinaryName?.takeUnless(String::isPlatformType)
        superclass?.let { builder.superclass(it.javaClassName(facadeNames)) }
        val superCall = superclass?.let { superConstructorCall(it, model.binaryName, siblings, facadeNames, classLoader) }
        model.declaredFields.values.filterNot(FieldModel::isSynthetic).forEach { field ->
            val modifiers = memberModifiers(field.isPublic, field.isProtected, field.isPrivate, field.isStatic, false)
            builder.addField(FieldSpec.builder(field.type.javaTypeName(facadeNames), field.name, *modifiers).build())
        }
        model.declaredMethods.asSequence()
            .filterNot(MethodModel::isSynthetic)
            .filter { SourceVersion.isIdentifier(it.name) && !SourceVersion.isKeyword(it.name) }
            .map { methodStub(it, facadeNames) }
            .forEach(builder::addMethod)
        model.declaredConstructors
            .map { constructor ->
                constructorStub(constructor, hasEnclosingInstance = !declaration.isStatic, facadeNames, superCall)
            }
            .forEach(builder::addMethod)
        return builder.build()
    }

    /**
     * The `super(...)` call a nested stub's constructors need when the superclass declares no constructor without
     * arguments: an accessible constructor, called with default values. A hierarchy facade declares no
     * constructors, so its default one serves and `null` is returned.
     */
    private fun superConstructorCall(
        superclassBinaryName: String,
        subclassBinaryName: String,
        siblings: List<Pair<NestedClassModel, ClassModel>>,
        facadeNames: Set<String>,
        classLoader: ClassLoader?,
    ): CodeBlock? {
        if (superclassBinaryName in facadeNames) return null
        val sibling = siblings.firstOrNull { (declaration, _) -> declaration.binaryName == superclassBinaryName }
        val model = sibling?.second ?: loadClassModel(superclassBinaryName, classLoader) ?: return null
        val enclosingInstanceParameters = if (sibling != null && !sibling.first.isStatic) 1 else 0
        val samePackage = superclassBinaryName.substringBeforeLast('.', "") ==
            subclassBinaryName.substringBeforeLast('.', "")
        val signatures = model.declaredConstructors.filter { constructor ->
            sibling != null || constructor.isPublic || constructor.isProtected ||
                (samePackage && !constructor.isPrivate)
        }.map {
            Type.getArgumentTypes(it.descriptor).drop(enclosingInstanceParameters)
        }
        if (signatures.isEmpty() || signatures.any { it.isEmpty() }) return null
        val arguments = signatures.first().map { type ->
            CodeBlock.of("(\$T) \$L", type.javaTypeName(facadeNames), type.defaultJavaValue())
        }
        return CodeBlock.of("super(\$L)", CodeBlock.join(arguments, ", "))
    }

    private fun constructorStub(
        constructor: ConstructorModel,
        hasEnclosingInstance: Boolean,
        facadeNames: Set<String>,
        superCall: CodeBlock?,
    ): MethodSpec {
        val builder = MethodSpec.constructorBuilder()
        when {
            constructor.isPublic -> builder.addModifiers(Modifier.PUBLIC)
            constructor.isProtected -> builder.addModifiers(Modifier.PROTECTED)
            constructor.isPrivate -> builder.addModifiers(Modifier.PRIVATE)
        }
        Type.getArgumentTypes(constructor.descriptor)
            .drop(if (hasEnclosingInstance) 1 else 0)
            .forEachIndexed { index, type ->
                builder.addParameter(type.javaTypeName(facadeNames), "p$index")
            }
        superCall?.let(builder::addStatement)
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

/**
 * A facade is a top-level class named by its binary simple name, `$` included, so a nested enclosing class
 * `Outer$Inner` and the classes nested in it are addressed through that flat name; every other class keeps its
 * nesting, which is how javac finds it on the classpath.
 */
private fun String.javaClassName(facadeNames: Set<String>): ClassName {
    val packageName = substringBeforeLast('.', "")
    val facade = facadeNames.filter { this == it || startsWith(it + "$") }.maxByOrNull { it.length }
    val simpleNames = if (facade == null) {
        substringAfterLast('.').split('$')
    } else {
        listOf(facade.substringAfterLast('.')) + removePrefix(facade).split('$').filter { it.isNotEmpty() }
    }
    return ClassName.get(packageName, simpleNames.first(), *simpleNames.drop(1).toTypedArray())
}

private fun String.isPlatformType(): Boolean =
    startsWith("java.") || startsWith("javax.") || startsWith("jdk.") || startsWith("sun.")

/** What decides whether two methods override or overload each other: the name and the parameter types. */
private data class MethodSignatureKey(val name: String, val parameterDescriptors: List<String>)

private fun MethodModel.signatureKey() =
    MethodSignatureKey(name, Type.getArgumentTypes(descriptor).map { it.descriptor })

private fun Type.javaTypeName(facadeNames: Set<String>): TypeName = when (sort) {
    Type.VOID -> TypeName.VOID
    Type.BOOLEAN -> TypeName.BOOLEAN
    Type.CHAR -> TypeName.CHAR
    Type.BYTE -> TypeName.BYTE
    Type.SHORT -> TypeName.SHORT
    Type.INT -> TypeName.INT
    Type.FLOAT -> TypeName.FLOAT
    Type.LONG -> TypeName.LONG
    Type.DOUBLE -> TypeName.DOUBLE
    Type.ARRAY -> ArrayTypeName.of(Type.getType(descriptor.substring(1)).javaTypeName(facadeNames))
    else -> className.javaClassName(facadeNames)
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
