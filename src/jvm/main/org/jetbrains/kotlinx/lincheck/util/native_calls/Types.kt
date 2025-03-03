/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util.native_calls

import org.objectweb.asm.Type.getArgumentTypes
import org.objectweb.asm.Type.getReturnType
import org.objectweb.asm.commons.Method

internal data class MethodSignature(val name: String, val methodType: MethodType)
internal data class MethodType(val argumentTypes: List<ArgumentType>, val returnType: Type)

internal sealed class ArgumentType: Type() {
    sealed class Primitive : ArgumentType() {
        data object Int : Primitive()
        data object Long : Primitive()
        data object Double : Primitive()
        data object Float : Primitive()
        data object Boolean : Primitive()
        data object Byte : Primitive()
        data object Short : Primitive()
        data object Char : Primitive()
    }
    data class Object(val className: String) : ArgumentType()
    data class Array(val type: ArgumentType) : ArgumentType()
}

internal sealed class Type {
    data object Void : Type()
}

private fun convertAsmArgumentTypeName(className: String): ArgumentType = when (className) {
    "I" -> ArgumentType.Primitive.Int
    "J" -> ArgumentType.Primitive.Long
    "D" -> ArgumentType.Primitive.Double
    "F" -> ArgumentType.Primitive.Float
    "Z" -> ArgumentType.Primitive.Boolean
    "B" -> ArgumentType.Primitive.Byte
    "S" -> ArgumentType.Primitive.Short
    "C" -> ArgumentType.Primitive.Char
    else -> if (className.startsWith("[")) {
        ArgumentType.Array(convertAsmArgumentTypeName(className.drop(1)))
    } else {
        require(className.startsWith("L") && className.endsWith(";")) {
            "Invalid type name: $className"
        }
        ArgumentType.Object(className.drop(1).dropLast(1).replace('/', '.'))
    }
}

private fun convertAsmTypeName(className: String): Type = when (className) {
    "V" -> Type.Void
    else -> convertAsmArgumentTypeName(className)
}

private fun convertAsmMethodType(methodDesc: String): MethodType {
    val argumentTypeStrings = getArgumentTypes(methodDesc)
    val returnType = getReturnType(methodDesc)
    return MethodType(
        argumentTypes = argumentTypeStrings.map { convertAsmArgumentTypeName(it.descriptor) },
        returnType = convertAsmTypeName(returnType.descriptor),
    )
}

internal fun Method.toMethodSignature() = MethodSignature(this.name, convertAsmMethodType(this.descriptor))
internal fun java.lang.reflect.Method.toMethodSignature() = Method.getMethod(this).toMethodSignature()
internal fun convertAsmMethodToMethodSignature(methodName: String, methodDesc: String) = MethodSignature(methodName, convertAsmMethodType(methodDesc))
