/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.kotlin

import org.jetbrains.lincheck.jvm.agent.ClassModel
import org.jetbrains.lincheck.jvm.agent.FieldModel
import org.jetbrains.lincheck.jvm.agent.MethodModel
import org.jetbrains.lincheck.jvm.agent.loadClassModel
import org.jetbrains.lincheck.jvm.agent.expressions.ApplicationClasses
import org.jetbrains.lincheck.jvm.agent.expressions.CapturedLocal
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionKind
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionWrapper
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionCompilationException
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionBytecodeRewriter
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionCompiler
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionEvaluatorTransplanter
import org.jetbrains.lincheck.jvm.agent.expressions.expressionClassNames
import org.jetbrains.lincheck.jvm.agent.expressions.expressionClasspath
import org.jetbrains.lincheck.jvm.agent.expressions.MockedApplicationMembers
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.objectweb.asm.Type
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.util.ArrayDeque

/**
 * Compiles a Kotlin expression in a disposable source copy of its enclosing application class.
 *
 * This is the language-specific middle of the [ExpressionCompiler] pipeline.
 * It reads class-file models without loading the application classes, renders only the members needed to resolve the
 * expression, and compiles that source together with an evaluator method.
 * It then asks [ExpressionEvaluatorTransplanter] to move the evaluator into the wrapper and
 * [ExpressionBytecodeRewriter] to replace accesses to mocked non-public members.
 *
 * Given a `Counter` with a private `count` property, local `limit`, and condition `count > limit`, the input to this
 * phase is the expression, capture types, `Counter`'s class model, and the wrapper source.
 * Its temporary source has this shape:
 * ```kotlin
 * open class Counter {
 *     @JvmField var count: Int = 0
 *     private fun __evaluate(limit: Int): Boolean = count > limit
 * }
 * ```
 * The temporary `Counter` is discarded; the output is the wrapper class files containing that evaluator.
 */
internal object KotlinEnclosingClassEvaluator {
    private const val EVALUATE_METHOD = "__evaluate"
    private const val MAX_GRAPH_CLASSES = 64

    /**
     * PSI supplies a conservative set of referenced names, while JVM descriptors drive a bounded class-graph walk.
     * Every visited application class is represented by source containing only members whose names can participate
     * in the expression. Superclass facades preserve inherited member lookup. A nested class is mocked as a top-level
     * class named by its binary simple name, so its own members resolve while its outer class's scope does not;
     * a file facade is mocked as a file of top-level declarations. This avoids loading classes, but cannot
     * reproduce object semantics, Kotlin extension resolution, delegated properties, metadata-only declarations,
     * or graphs beyond [MAX_GRAPH_CLASSES]. Lambdas are rejected because their generated classes are not relocated
     * with the evaluator.
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
    ): Map<String, ByteArray> {
        require(!enclosing.isInterface) {
            "Agent-side Kotlin expressions using an enclosing receiver are not yet supported in interfaces"
        }

        val evaluationCaptures = captures.filterNot { it.name == ExpressionWrapper.INSTANCE_FIELD }
        val expressionNames = KotlinExpressionToolchain.expressionNames(expressions)
        val referencedNames = expressionNames.referenced
        val propertyNames = referencedNames - expressionNames.called
        val graph = discoverClassGraph(
            enclosing, captures, referencedNames.withPropertyJvmNames(), propertyNames, classLoader,
        )
        val companion = enclosing.nestedClasses.singleOrNull { it.simpleName == "Companion" }
            ?.let { loadClassModel(it.binaryName, classLoader) }
            ?.let { candidateClass(it, referencedNames.withPropertyJvmNames(), propertyNames) }
        val sources = graph.values.associate { candidate ->
            candidate.model.binaryName.replace('.', '/') + ".kt" to renderMock(
                candidate, expressions.takeIf { candidate.model.binaryName == enclosing.binaryName },
                kind, evaluationCaptures, receiverCapture != null, classLoader, graph.keys,
                inheritedMethodSignatures(candidate, graph),
                inheritedPropertyNames(candidate, graph),
                companion.takeIf { candidate.model.binaryName == enclosing.binaryName },
            )
        }
        val requiredClassNames = expressionClassNames(
            graph.values.map { it.model } + listOfNotNull(companion?.model),
            captures.map { it.type },
        )
        val applicationClasses = ApplicationClasses(
            classLoader, graph.values.map(CandidateClass::model) + listOfNotNull(companion?.model),
        )
        // The facade and the wrapper resolve the same application types, so they share one prepared classpath.
        return expressionClasspath(classLoader, requiredClassNames).use { classpath ->
            val facadeClasses = KotlinExpressionToolchain.compile(sources, classpath)
            // The evaluator sits where the expression's names resolve as in the application: in the class for an
            // instance method, at top level for a file facade, and otherwise in the companion object.
            val evaluatorOwner = when {
                receiverCapture != null || enclosing.isKotlinFileFacade -> enclosing.binaryName
                else -> "${enclosing.binaryName}\$Companion"
            }
            val facadeBytes = facadeClasses[evaluatorOwner]
                ?: throw ExpressionCompilationException("Expression compiler produced no enclosing-class facade")

            val wrapperClasses = KotlinExpressionToolchain.compile(
                mapOf(wrapperBinaryName.replace('.', '/') + ".kt" to wrapperSource), classpath,
            )
            val wrapperBytes = wrapperClasses[wrapperBinaryName]
                ?: throw ExpressionCompilationException("Expression compiler produced no wrapper class")

            val transplanted = ExpressionEvaluatorTransplanter.transplant(
                facadeBytes = facadeBytes,
                wrapperBytes = wrapperBytes,
                evaluatorMethodName = EVALUATE_METHOD,
                languageName = "Kotlin",
                evaluatorReceiverType = when {
                    receiverCapture != null -> Type.getObjectType(enclosing.binaryName.replace('.', '/'))
                    enclosing.isKotlinFileFacade -> null
                    else -> Type.getType(Object::class.java)
                },
                receiverCapture = receiverCapture,
                evaluationCaptures = evaluationCaptures,
                kind = kind,
                applicationClasses = applicationClasses,
                prepareEvaluator = ::removeParameterNullChecks,
            )

            val mockedMembers = MockedApplicationMembers()
            mockedMembers.addNonPublicMembers(graph.values.map(CandidateClass::model))
            companion?.model?.let { mockedMembers.addNonPublicMembers(listOf(it)) }
            val result = wrapperClasses.toMutableMap().apply { put(wrapperBinaryName, transplanted) }
            ExpressionBytecodeRewriter.rewrite(result, mockedMembers, applicationClasses)
        }
    }

    private fun discoverClassGraph(
        enclosing: ClassModel,
        captures: List<CapturedLocal>,
        names: Set<String>,
        propertyNames: Set<String>,
        classLoader: ClassLoader?,
    ): LinkedHashMap<String, CandidateClass> {
        val result = LinkedHashMap<String, CandidateClass>()
        val pending = ArrayDeque<ClassModel>()
        pending += enclosing
        captures.mapNotNull { loadModel(it.type, classLoader) }.forEach(pending::add)
        while (pending.isNotEmpty() && result.size < MAX_GRAPH_CLASSES) {
            val model = pending.removeFirst()
            if (model.binaryName in result || model.binaryName.isPlatformType()) continue
            val candidate = candidateClass(model, names, propertyNames)
            result[model.binaryName] = candidate
            model.superclassBinaryName
                ?.takeUnless(String::isPlatformType)
                ?.let { loadClassModel(it, classLoader) }
                ?.let(pending::addFirst)
            candidate.fields.mapNotNull { loadModel(it.type, classLoader) }.forEach(pending::add)
            candidate.methods.asSequence()
                .flatMap { method ->
                    Type.getArgumentTypes(method.descriptor).asSequence() + Type.getReturnType(method.descriptor)
                }
                .mapNotNull { loadModel(it, classLoader) }
                .forEach(pending::add)
        }
        return result
    }

    private fun candidateClass(model: ClassModel, names: Set<String>, propertyNames: Set<String>): CandidateClass {
        val candidateMethods = model.declaredMethods.filter {
            !it.isSynthetic && it.name in names && it.name != EVALUATE_METHOD
        }.distinctBy { it.name to Type.getArgumentTypes(it.descriptor).joinToString { type -> type.descriptor } }
        val properties = candidateMethods.mapNotNull { method -> method.getterProperty(propertyNames) }
        val fields = model.declaredFields.values.filter { field ->
            field.name in names && properties.none { it.name == field.name }
        }
        val methods = candidateMethods.filterNot { method -> properties.any { it.getter === method } }
        return CandidateClass(model, fields, methods, properties)
    }

    private fun loadModel(type: Type, classLoader: ClassLoader?): ClassModel? {
        val element = generateSequence(type) { current ->
            current.takeIf { it.sort == Type.ARRAY }?.let { Type.getType(it.descriptor.substring(1)) }
        }.firstOrNull { it.sort != Type.ARRAY } ?: return null
        if (element.sort != Type.OBJECT || element.className.isPlatformType()) return null
        val resourceName = element.internalName + ".class"
        val stream = classLoader?.getResourceAsStream(resourceName)
            ?: ClassLoader.getSystemResourceAsStream(resourceName)
            ?: return null
        return stream.use { ClassModel.fromClassBytes(it.readBytes()) }
    }

    private fun inheritedMethodSignatures(
        candidate: CandidateClass,
        graph: Map<String, CandidateClass>,
    ): Set<MethodSignature> = buildSet {
        var superclass = candidate.model.superclassBinaryName
        while (superclass != null) {
            val inherited = graph[superclass] ?: break
            inherited.methods.filter { !it.isStatic && !it.isPrivate }.mapTo(this) { it.signature() }
            superclass = inherited.model.superclassBinaryName
        }
    }

    private fun inheritedPropertyNames(
        candidate: CandidateClass,
        graph: Map<String, CandidateClass>,
    ): Set<String> = buildSet {
        var superclass = candidate.model.superclassBinaryName
        while (superclass != null) {
            val inherited = graph[superclass] ?: break
            inherited.properties.filter { !it.getter.isStatic && !it.getter.isPrivate }.mapTo(this) { it.name }
            superclass = inherited.model.superclassBinaryName
        }
    }

    private fun renderMock(
        candidate: CandidateClass,
        expressions: List<String>?,
        kind: ExpressionKind,
        captures: List<CapturedLocal>,
        hasReceiver: Boolean,
        classLoader: ClassLoader?,
        classNames: Set<String>,
        inheritedMethodSignatures: Set<MethodSignature>,
        inheritedPropertyNames: Set<String>,
        companionCandidate: CandidateClass?,
    ): String {
        val model = candidate.model
        val packageName = model.binaryName.substringBeforeLast('.', "")
        val simpleName = model.binaryName.substringAfterLast('.')
        if (model.isKotlinFileFacade) {
            return renderFileFacadeMock(candidate, packageName, simpleName, expressions, kind, captures, classLoader)
        }
        val type = TypeSpec.classBuilder(simpleName).addModifiers(KModifier.OPEN)
        model.superclassBinaryName?.takeIf { it in classNames }?.let { superclass ->
            type.superclass(KotlinWrapperSource.typeName(Type.getObjectType(superclass.replace('.', '/')), classLoader))
        }
        candidate.fields.filterNot(FieldModel::isStatic).forEach { field ->
            type.addProperty(field.property(classLoader))
        }
        candidate.properties.filterNot { it.getter.isStatic }.forEach { property ->
            val modifier = when {
                property.name in inheritedPropertyNames -> KModifier.OVERRIDE
                !property.getter.isPrivate -> KModifier.OPEN
                else -> null
            }
            type.addProperty(property.stub(classLoader, modifier))
        }
        candidate.methods.filterNot(MethodModel::isStatic).map { method ->
            val modifier = when {
                method.signature() in inheritedMethodSignatures -> KModifier.OVERRIDE
                !method.isPrivate -> KModifier.OPEN
                else -> null
            }
            method.stub(classLoader, modifier)
        }.forEach(type::addFunction)

        val companion = TypeSpec.companionObjectBuilder()
        candidate.fields.filter(FieldModel::isStatic).forEach { field ->
            companion.addProperty(field.property(classLoader))
        }
        candidate.properties.filter { it.getter.isStatic }.forEach { property ->
            companion.addProperty(property.stub(classLoader))
        }
        candidate.methods.filter(MethodModel::isStatic).forEach { method ->
            companion.addFunction(method.stub(classLoader).toBuilder().addAnnotation(JvmStatic::class).build())
        }
        companionCandidate?.fields?.forEach { field ->
            companion.addProperty(field.property(classLoader))
        }
        companionCandidate?.properties?.forEach { property ->
            companion.addProperty(property.stub(classLoader))
        }
        companionCandidate?.methods?.forEach { method ->
            companion.addFunction(method.stub(classLoader))
        }
        if (expressions != null) {
            val evaluator = evaluator(expressions, kind, captures, classLoader)
            if (hasReceiver) type.addFunction(evaluator) else companion.addFunction(evaluator)
        }
        if (companion.build().propertySpecs.isNotEmpty() || companion.build().funSpecs.isNotEmpty()) {
            type.addType(companion.build())
        }
        return FileSpec.builder(packageName, simpleName).addType(type.build()).build().toString()
    }

    /**
     * A file facade holds top-level declarations, so its mock is a file of top-level declarations compiled to the
     * same facade class; a call to one of them then targets that class, as it does in the application.
     */
    private fun renderFileFacadeMock(
        candidate: CandidateClass,
        packageName: String,
        facadeName: String,
        expressions: List<String>?,
        kind: ExpressionKind,
        captures: List<CapturedLocal>,
        classLoader: ClassLoader?,
    ): String {
        val file = FileSpec.builder(packageName, facadeName.removeSuffix("Kt"))
            .addAnnotation(
                AnnotationSpec.builder(JvmName::class)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%S", facadeName)
                    .build(),
            )
        candidate.fields.filter(FieldModel::isStatic).forEach { file.addProperty(it.property(classLoader)) }
        candidate.properties.filter { it.getter.isStatic }.forEach { file.addProperty(it.stub(classLoader)) }
        candidate.methods.filter(MethodModel::isStatic).forEach { file.addFunction(it.stub(classLoader)) }
        expressions?.let { file.addFunction(evaluator(it, kind, captures, classLoader)) }
        return file.build().toString()
    }

    private fun evaluator(
        expressions: List<String>,
        kind: ExpressionKind,
        captures: List<CapturedLocal>,
        classLoader: ClassLoader?,
    ): FunSpec {
        val function = FunSpec.builder(EVALUATE_METHOD).addModifiers(KModifier.PRIVATE)
        captures.forEachIndexed { index, capture ->
            function.addParameter(
                capture.name.ifEmpty { "arg$index" },
                KotlinWrapperSource.typeName(capture.type, classLoader),
            )
        }
        return when (kind) {
            ExpressionKind.CONDITION -> function.returns(BOOLEAN)
                .addStatement("return·(%L)", expressions.single()).build()
            ExpressionKind.WATCHES -> {
                function.returns(ARRAY.parameterizedBy(ANY.copy(nullable = true)))
                function.addCode("return·arrayOf(\n⇥")
                expressions.forEach { function.addCode("(%L)·as·Any?,\n", it) }
                function.addCode("⇤)\n").build()
            }
        }
    }

    private fun FieldModel.property(classLoader: ClassLoader?) = PropertySpec.builder(
        name, KotlinWrapperSource.typeName(type, classLoader),
    ).addAnnotation(JvmField::class).mutable(true).initializer(type.defaultKotlinValue(classLoader)).build()

    private fun GetterProperty.stub(classLoader: ClassLoader?, modifier: KModifier? = null): PropertySpec {
        val returnType = Type.getReturnType(getter.descriptor)
        val getter = FunSpec.getterBuilder()
            .addStatement("return·%L", returnType.defaultKotlinValue(classLoader))
            .build()
        val property = PropertySpec.builder(name, KotlinWrapperSource.typeName(returnType, classLoader))
            .getter(getter)
        if (modifier != null) property.addModifiers(modifier)
        return property.build()
    }

    private fun MethodModel.stub(classLoader: ClassLoader?, modifier: KModifier? = null): FunSpec {
        val methodType = Type.getMethodType(descriptor)
        val function = FunSpec.builder(name).returns(KotlinWrapperSource.typeName(methodType.returnType, classLoader))
        if (modifier != null) function.addModifiers(modifier)
        methodType.argumentTypes.forEachIndexed { index, type ->
            val parameterType = if (isVarArgs && index == methodType.argumentTypes.lastIndex) {
                Type.getType(type.descriptor.substring(1))
            } else {
                type
            }
            function.addParameter(
                ParameterSpec.builder("p$index", KotlinWrapperSource.typeName(parameterType, classLoader))
                    .apply {
                        if (isVarArgs && index == methodType.argumentTypes.lastIndex) addModifiers(KModifier.VARARG)
                    }
                    .build(),
            )
        }
        if (methodType.returnType.sort != Type.VOID) function.addStatement(
            "return·%L", methodType.returnType.defaultKotlinValue(classLoader),
        )
        return function.build()
    }

    private fun removeParameterNullChecks(method: org.objectweb.asm.tree.MethodNode) {
        method.instructions.toArray().filterIsInstance<MethodInsnNode>().forEach { call ->
            if (call.owner != "kotlin/jvm/internal/Intrinsics" || call.name != "checkNotNullParameter") return@forEach
            val message = call.previous as? LdcInsnNode ?: return@forEach
            val value = message.previous as? VarInsnNode ?: return@forEach
            method.instructions.remove(value)
            method.instructions.remove(message)
            method.instructions.remove(call)
        }
    }

    private data class CandidateClass(
        val model: ClassModel,
        val fields: List<FieldModel>,
        val methods: List<MethodModel>,
        val properties: List<GetterProperty>,
    )

}

private data class GetterProperty(val name: String, val getter: MethodModel)

private data class MethodSignature(val name: String, val parameterDescriptors: List<String>)

private fun MethodModel.signature() = MethodSignature(
    name,
    Type.getArgumentTypes(descriptor).map { it.descriptor },
)

private fun MethodModel.getterProperty(propertyNames: Set<String>): GetterProperty? {
    val type = Type.getMethodType(descriptor)
    if (type.argumentTypes.isNotEmpty() || type.returnType.sort == Type.VOID) return null
    val propertyName = when {
        name.startsWith("get") && name.length > 3 -> name.substring(3).replaceFirstChar { it.lowercase() }
        name.startsWith("is") && name.length > 2 && type.returnType.sort == Type.BOOLEAN -> name
        else -> return null
    }
    return propertyName.takeIf { it in propertyNames }?.let {
        GetterProperty(it, this)
    }
}

private fun Set<String>.withPropertyJvmNames(): Set<String> = buildSet {
    addAll(this@withPropertyJvmNames)
    this@withPropertyJvmNames.forEach { name ->
        val capitalized = name.replaceFirstChar { it.uppercase() }
        add("get$capitalized")
        add("is$capitalized")
        add("set$capitalized")
    }
}

private fun String.isPlatformType(): Boolean =
    startsWith("java.") || startsWith("javax.") || startsWith("jdk.") || startsWith("sun.") || startsWith("kotlin.")

private fun Type.defaultKotlinValue(classLoader: ClassLoader?): CodeBlock {
    val literal = when (sort) {
        Type.VOID -> "Unit"
        Type.BOOLEAN -> "false"
        Type.CHAR -> "'\\u0000'"
        Type.BYTE, Type.SHORT, Type.INT -> "0"
        Type.FLOAT -> "0.0f"
        Type.LONG -> "0L"
        Type.DOUBLE -> "0.0"
        else -> return CodeBlock.of("null·as·%T", KotlinWrapperSource.typeName(this, classLoader))
    }
    return CodeBlock.of(literal)
}
