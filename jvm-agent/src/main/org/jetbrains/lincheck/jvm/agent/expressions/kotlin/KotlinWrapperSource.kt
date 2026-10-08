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

import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.loadClassModel
import org.jetbrains.lincheck.jvm.agent.expressions.CapturedLocal
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionCompiler
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionEvaluatorTransplanter
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionKind
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionWrapper
import org.jetbrains.lincheck.jvm.agent.expressions.isPlatformClass

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.CHAR
import com.squareup.kotlinpoet.CHAR_SEQUENCE
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import org.objectweb.asm.Type

/**
 * Builds the Kotlin runtime-wrapper source before the user's expression is compiled.
 *
 * [ExpressionCompiler] selects the captures and passes them here.
 * This builder emits their fields, the supplier bridge, `createFactory()`, and a placeholder `invoke()`.
 * [KotlinEnclosingClassEvaluator] compiles the expression separately, then [ExpressionEvaluatorTransplanter]
 * replaces the placeholder with the compiled evaluator call.
 *
 * For the condition `count > limit`, with captures `limit` and `__instance`, this phase produces the shape:
 * ```kotlin
 * class Wrapper(args: Array<Any?>) : BooleanSupplier {
 *     @JvmField val limit: Int = __capture(args[0])
 *     @JvmField val __instance: Counter = __capture(args[1])
 *     fun invoke(): Boolean = false // replaced later
 *     override fun getAsBoolean() = invoke()
 *     companion object { @JvmStatic fun createFactory() = Function { args -> Wrapper(args) } }
 * }
 * ```
 * See [ExpressionWrapper] for the complete wrapper contract.
 */
internal object KotlinWrapperSource {

    fun render(
        packageName: String?,
        simpleName: String,
        captures: List<CapturedLocal>,
        kind: ExpressionKind,
        classLoader: ClassLoader?,
    ): String {
        val nullableAny = ANY.copy(nullable = true)
        val argsArray = ARRAY.parameterizedBy(nullableAny)
        val samType: TypeName
        val resultType: TypeName
        val bridge: FunSpec
        when (kind) {
            ExpressionKind.CONDITION -> {
                samType = ClassName("java.util.function", "BooleanSupplier")
                resultType = BOOLEAN
                bridge = FunSpec.builder("getAsBoolean")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(BOOLEAN)
                    .addStatement("return·invoke()")
                    .build()
            }
            ExpressionKind.WATCHES -> {
                samType = ClassName("java.util.function", "Supplier").parameterizedBy(argsArray)
                resultType = argsArray
                bridge = FunSpec.builder("get")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(argsArray)
                    .addStatement("return·invoke()")
                    .build()
            }
        }

        val wrapper = TypeSpec.classBuilder(simpleName)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("args", argsArray).build())
            .addSuperinterface(samType)
        // Captures transport through the generic unchecked `__capture` helper: the
        // LocalVariableTable cannot establish Kotlin nullability, and a plain `as T` cast would
        // throw on a legitimately null value before the expression (e.g. `value == null`) could
        // even see it — the JVM's own checkcast lets null through.
        captures.forEachIndexed { i, capture ->
            wrapper.addProperty(
                PropertySpec.builder(capture.name, typeName(ExpressionWrapper.storageType(capture.type), classLoader))
                    .addAnnotation(JvmField::class)
                    .initializer("__capture(args[%L])", i)
                    .build(),
            )
        }

        val invoke = FunSpec.builder("invoke").returns(resultType)
        when (kind) {
            ExpressionKind.CONDITION ->
                invoke.addStatement("return·false")
            ExpressionKind.WATCHES -> {
                invoke.addStatement("return·%L()", ExpressionWrapper.WATCH_VALUES_HELPER)
                val watchValues = FunSpec.builder(ExpressionWrapper.WATCH_VALUES_HELPER)
                    .addModifiers(KModifier.PRIVATE)
                    .returns(argsArray)
                    .addStatement("return·emptyArray()")
                wrapper.addFunction(watchValues.build())
            }
        }
        wrapper.addFunction(invoke.build())
        wrapper.addFunction(bridge)

        wrapper.addType(
            TypeSpec.companionObjectBuilder()
                .addFunction(
                    FunSpec.builder("createFactory")
                        .addAnnotation(JvmStatic::class)
                        .returns(ClassName("java.util.function", "Function").parameterizedBy(argsArray, samType))
                        .addStatement(
                            "return·%T·{·args·->·%L(args)·}",
                            ClassName("java.util.function", "Function"),
                            simpleName,
                        )
                        .build(),
                )
                .addFunction(
                    FunSpec.builder("__capture")
                        .addModifiers(KModifier.PRIVATE)
                        .addTypeVariable(TypeVariableName("T"))
                        .addAnnotation(
                            AnnotationSpec.builder(Suppress::class).addMember("%S", "UNCHECKED_CAST").build(),
                        )
                        .addParameter("value", nullableAny)
                        .returns(TypeVariableName("T"))
                        .addStatement("return·value·as·T")
                        .build(),
                )
                .build(),
        )

        return FileSpec.builder(packageName.orEmpty(), simpleName)
            .addType(wrapper.build())
            .build()
            .toString()
    }

    /**
     * The KotlinPoet type of a JVM type. Generic classes are star-projected (arity read from the class file found
     * through [classLoader], since Kotlin has no raw types and loading the class is not an option while it may be
     * in its own definition); `java.lang` types map to their Kotlin counterparts, boxed primitives to the nullable
     * ones. Captures are declared non-null; null transports through `__capture`.
     */
    internal fun typeName(type: Type, classLoader: ClassLoader?): TypeName = when (type.sort) {
        Type.VOID -> UNIT
        Type.BOOLEAN -> BOOLEAN
        Type.CHAR -> CHAR
        Type.BYTE -> BYTE
        Type.SHORT -> SHORT
        Type.INT -> INT
        Type.LONG -> LONG
        Type.FLOAT -> FLOAT
        Type.DOUBLE -> DOUBLE
        Type.ARRAY -> {
            val element = type.elementType
            if (type.dimensions == 1 && element.sort != Type.OBJECT && element.sort != Type.ARRAY) {
                primitiveArrayName(element)
            } else {
                ARRAY.parameterizedBy(typeName(Type.getType(type.descriptor.substring(1)), classLoader))
            }
        }
        else -> when (val name = type.className) {
            "java.lang.String" -> STRING
            "java.lang.Object" -> ANY
            "java.lang.CharSequence" -> CHAR_SEQUENCE
            "java.lang.Boolean" -> BOOLEAN.copy(nullable = true)
            "java.lang.Character" -> CHAR.copy(nullable = true)
            "java.lang.Byte" -> BYTE.copy(nullable = true)
            "java.lang.Short" -> SHORT.copy(nullable = true)
            "java.lang.Integer" -> INT.copy(nullable = true)
            "java.lang.Long" -> LONG.copy(nullable = true)
            "java.lang.Float" -> FLOAT.copy(nullable = true)
            "java.lang.Double" -> DOUBLE.copy(nullable = true)
            else -> {
                val className = className(name)
                val arity = loadClassModel(name, classLoader)?.typeParameterCount ?: 0
                if (arity == 0) className else className.parameterizedBy(List(arity) { STAR })
            }
        }
    }

    private fun primitiveArrayName(element: Type): ClassName = when (element.sort) {
        Type.BOOLEAN -> ClassName("kotlin", "BooleanArray")
        Type.CHAR -> ClassName("kotlin", "CharArray")
        Type.BYTE -> ClassName("kotlin", "ByteArray")
        Type.SHORT -> ClassName("kotlin", "ShortArray")
        Type.INT -> ClassName("kotlin", "IntArray")
        Type.LONG -> ClassName("kotlin", "LongArray")
        Type.FLOAT -> ClassName("kotlin", "FloatArray")
        else -> ClassName("kotlin", "DoubleArray")
    }

    /**
     * A [ClassName] from a binary name. Application classes are mocked as top-level classes named by their binary
     * simple name, `$` included (KotlinPoet backticks it), so a nested `Outer$Inner` is addressed that way; platform
     * classes come from real class files and keep their nesting.
     */
    private fun className(binaryName: String): ClassName {
        val packageName = binaryName.substringBeforeLast('.', "")
        val simpleName = binaryName.substringAfterLast('.')
        return if (binaryName.isPlatformClass()) ClassName(packageName, simpleName.split('$')) else ClassName(packageName, simpleName)
    }
}
