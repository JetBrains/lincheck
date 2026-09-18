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

import org.jetbrains.lincheck.jvm.agent.expressions.CapturedLocal
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionCompiler
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionEvaluatorTransplanter
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionKind
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionWrapper

import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import org.objectweb.asm.Type
import javax.lang.model.element.Modifier

/**
 * Builds the Java runtime-wrapper source before the user's expression is compiled.
 *
 * [ExpressionCompiler] selects the captures and passes them here.
 * This builder emits their fields, the supplier bridge, `createFactory()`, and a placeholder `invoke()`.
 * [JavaEnclosingClassEvaluator] compiles the expression separately, then [ExpressionEvaluatorTransplanter]
 * replaces the placeholder with the compiled evaluator call.
 *
 * For the condition `count > limit`, with captures `limit` and `__instance`, this phase produces the shape:
 * ```java
 * final class Wrapper implements BooleanSupplier {
 *     int limit; Counter __instance;
 *     private boolean invoke() { return false; } // replaced later
 *     public boolean getAsBoolean() { return invoke(); }
 *     public static Function<Object[], BooleanSupplier> createFactory() { ... }
 * }
 * ```
 * See [ExpressionWrapper] for the complete wrapper contract.
 */
internal object JavaWrapperSource {

    fun render(
        packageName: String?,
        simpleName: String,
        captures: List<CapturedLocal>,
        kind: ExpressionKind,
    ): String {
        // `createFactory()` fills capture fields from Object[] and returns the requested SAM.

        val objectArray = ArrayTypeName.of(TypeName.OBJECT)
        val samType: TypeName
        val resultType: TypeName
        val bridge: MethodSpec
        when (kind) {
            ExpressionKind.CONDITION -> {
                samType = ClassName.get("java.util.function", "BooleanSupplier")
                resultType = TypeName.BOOLEAN
                bridge = MethodSpec.methodBuilder("getAsBoolean")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.BOOLEAN)
                    .addStatement("return invoke()")
                    .build()
            }
            ExpressionKind.WATCHES -> {
                samType = ParameterizedTypeName.get(ClassName.get("java.util.function", "Supplier"), objectArray)
                resultType = objectArray
                bridge = MethodSpec.methodBuilder("get")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(objectArray)
                    .addStatement("return invoke()")
                    .build()
            }
        }

        val wrapper = TypeSpec.classBuilder(simpleName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(samType)
        for (capture in captures) {
            wrapper.addField(typeName(capture.type), capture.name)
        }
        wrapper.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())

        val factoryType = ParameterizedTypeName.get(
            ClassName.get("java.util.function", "Function"), objectArray, samType,
        )
        val apply = MethodSpec.methodBuilder("apply")
            .addModifiers(Modifier.PUBLIC)
            .returns(samType)
            .addParameter(objectArray, "args")
            .addStatement("\$1L expression = new \$1L()", simpleName)
        captures.forEachIndexed { i, capture ->
            apply.addStatement("expression.\$L = (\$T) args[\$L]", capture.name, typeName(capture.type).box(), i)
        }
        apply.addStatement("return expression")
        val factory = TypeSpec.anonymousClassBuilder("")
            .addSuperinterface(factoryType)
            .addMethod(apply.build())
            .build()
        wrapper.addMethod(
            MethodSpec.methodBuilder("createFactory")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(factoryType)
                .addStatement("return \$L", factory)
                .build(),
        )
        wrapper.addMethod(bridge)

        val invoke = MethodSpec.methodBuilder("invoke")
            .addModifiers(Modifier.PRIVATE)
            .returns(resultType)
        when (kind) {
            ExpressionKind.CONDITION ->
                invoke.addStatement("return false")
            ExpressionKind.WATCHES -> {
                invoke.addStatement("return \$L()", ExpressionWrapper.WATCH_VALUES_HELPER)
                val watchValues = MethodSpec.methodBuilder(ExpressionWrapper.WATCH_VALUES_HELPER)
                    .addModifiers(Modifier.PRIVATE)
                    .returns(objectArray)
                    .addStatement("return new \$T[0]", TypeName.OBJECT)
                wrapper.addMethod(watchValues.build())
            }
        }
        wrapper.addMethod(invoke.build())

        return JavaFile.builder(packageName.orEmpty(), wrapper.build()).build().toString()
    }

    /** The JavaPoet type of a JVM type; primitives and arrays included. */
    private fun typeName(type: Type): TypeName = when (type.sort) {
        Type.BOOLEAN -> TypeName.BOOLEAN
        Type.CHAR -> TypeName.CHAR
        Type.BYTE -> TypeName.BYTE
        Type.SHORT -> TypeName.SHORT
        Type.INT -> TypeName.INT
        Type.LONG -> TypeName.LONG
        Type.FLOAT -> TypeName.FLOAT
        Type.DOUBLE -> TypeName.DOUBLE
        Type.ARRAY -> ArrayTypeName.of(typeName(Type.getType(type.descriptor.substring(1))))
        else -> className(type.className)
    }

    /** A [ClassName] from a binary name, `$`-separated nesting included. */
    private fun className(binaryName: String): ClassName {
        val packageName = binaryName.substringBeforeLast('.', "")
        val simpleNames = binaryName.substringAfterLast('.').split('$')
        return ClassName.get(packageName, simpleNames.first(), *simpleNames.drop(1).toTypedArray())
    }
}
