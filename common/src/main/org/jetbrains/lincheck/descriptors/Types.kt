/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.descriptors

import org.jetbrains.lincheck.descriptors.Types.ArrayType
import org.jetbrains.lincheck.descriptors.Types.BOOLEAN_TYPE
import org.jetbrains.lincheck.descriptors.Types.BYTE_TYPE
import org.jetbrains.lincheck.descriptors.Types.CHAR_TYPE
import org.jetbrains.lincheck.descriptors.Types.DOUBLE_TYPE
import org.jetbrains.lincheck.descriptors.Types.FLOAT_TYPE
import org.jetbrains.lincheck.descriptors.Types.INT_TYPE
import org.jetbrains.lincheck.descriptors.Types.LONG_TYPE
import org.jetbrains.lincheck.descriptors.Types.SHORT_TYPE
import org.jetbrains.lincheck.descriptors.Types.BOOLEAN_TYPE_BOXED
import org.jetbrains.lincheck.descriptors.Types.BYTE_TYPE_BOXED
import org.jetbrains.lincheck.descriptors.Types.CHAR_TYPE_BOXED
import org.jetbrains.lincheck.descriptors.Types.DOUBLE_TYPE_BOXED
import org.jetbrains.lincheck.descriptors.Types.FLOAT_TYPE_BOXED
import org.jetbrains.lincheck.descriptors.Types.INT_TYPE_BOXED
import org.jetbrains.lincheck.descriptors.Types.LONG_TYPE_BOXED
import org.jetbrains.lincheck.descriptors.Types.SHORT_TYPE_BOXED
import org.jetbrains.lincheck.descriptors.Types.OBJECT_TYPE
import org.jetbrains.lincheck.descriptors.Types.ObjectType
import org.jetbrains.lincheck.descriptors.Types.Type
import java.util.*
import kotlin.math.max
import kotlin.reflect.KClass

object Types {
    fun convertAsmTypeName(asmType: org.objectweb.asm.Type): Type =
        convertAsmTypeName(asmType.descriptor)

    fun convertAsmTypeName(className: String): Type {
        return when (className) {
            "V" -> VOID_TYPE
            "I" -> INT_TYPE
            "J" -> LONG_TYPE
            "D" -> DOUBLE_TYPE
            "F" -> FLOAT_TYPE
            "Z" -> BOOLEAN_TYPE
            "B" -> BYTE_TYPE
            "S" -> SHORT_TYPE
            "C" -> CHAR_TYPE
            else if (className.startsWith("[")) ->
                ArrayType(convertAsmTypeName(className.substring(1)))
            else ->
                // Class name might be given in wrapping L and ; symbols or without them.
                // L and ; might be missing when the string `className` representation is retrieved from the
                // asm `Type::getDescriptor` method, which removes these symbols for non-internal OBJECT types.
                // See the method's javadoc for details.
                ObjectType(className
                    .run { if (startsWith("L") && endsWith(";")) substring(1, length - 1) else this }
                    .replace('/', '.')
                )
        }
    }

    /**
     * Parses each descriptor substring of the `methodDesc`, including the return type of the method.
     *
     * @param methodDesc descriptor of the method.
     */
    fun convertAsmMethodType(methodDesc: String): MethodType {
        // Modified code for parsing the type descriptors
        // in the method descriptor (which looks like this: "(args...)ret")
        val argumentTypes: MutableList<Type> = ArrayList<Type>()
        var currentOffset = 1

        while (methodDesc[currentOffset] != ')') {
            val currentDescriptorTypeOffset = currentOffset
            while (methodDesc[currentOffset] == '[') {
                currentOffset++
            }
            if (methodDesc[currentOffset++] == 'L') {
                val semiColumnOffset = methodDesc.indexOf(';', currentOffset)
                currentOffset = max(currentOffset, semiColumnOffset + 1)
            }
            argumentTypes.add(
                convertAsmTypeName(methodDesc.substring(currentDescriptorTypeOffset, currentOffset))
            )
        }
        val returnType = convertAsmTypeName(methodDesc.substring(currentOffset + 1))

        return MethodType(argumentTypes, returnType)
    }

    fun isPrimitive(type: Type?): Boolean {
        return (
            type is IntType ||
            type is LongType ||
            type is DoubleType ||
            type is FloatType ||
            type is BooleanType ||
            type is ByteType ||
            type is ShortType ||
            type is CharType
        )
    }

    val VOID_TYPE: VoidType = VoidType()

    val INT_TYPE: IntType = IntType()
    val LONG_TYPE: LongType = LongType()
    val DOUBLE_TYPE: DoubleType = DoubleType()
    val FLOAT_TYPE: FloatType = FloatType()
    val BOOLEAN_TYPE: BooleanType = BooleanType()
    val BYTE_TYPE: ByteType = ByteType()
    val SHORT_TYPE: ShortType = ShortType()
    val CHAR_TYPE: CharType = CharType()

    val INT_TYPE_BOXED: ObjectType = ObjectType("java/lang/Integer")
    val LONG_TYPE_BOXED: ObjectType = ObjectType("java/lang/Long")
    val DOUBLE_TYPE_BOXED: ObjectType = ObjectType("java/lang/Double")
    val FLOAT_TYPE_BOXED: ObjectType = ObjectType("java/lang/Float")
    val BOOLEAN_TYPE_BOXED: ObjectType = ObjectType("java/lang/Boolean")
    val BYTE_TYPE_BOXED: ObjectType = ObjectType("java/lang/Byte")
    val SHORT_TYPE_BOXED: ObjectType = ObjectType("java/lang/Short")
    val CHAR_TYPE_BOXED: ObjectType = ObjectType("java/lang/Character")

    val OBJECT_TYPE: ObjectType = ObjectType(Object::class.java.name)

    sealed class Type

    class ObjectType(val className: String) : Type() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ObjectType) return false

            return className == other.className
        }

        override fun hashCode(): Int {
            return Objects.hash(className)
        }

        override fun toString(): String {
            return className
        }
    }

    class ArrayType(val elementType: Type) : Type() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ArrayType) return false

            return elementType == other.elementType
        }

        override fun hashCode(): Int {
            return Objects.hash(elementType)
        }

        override fun toString(): String {
            return "$elementType[]"
        }
    }

    class VoidType internal constructor() : Type() {
        override fun toString(): String {
            return "void"
        }
    }

    class IntType internal constructor() : Type() {
        override fun toString(): String {
            return "int"
        }
    }

    class LongType internal constructor() : Type() {
        override fun toString(): String {
            return "long"
        }
    }

    class DoubleType internal constructor() : Type() {
        override fun toString(): String {
            return "double"
        }
    }

    class FloatType internal constructor() : Type() {
        override fun toString(): String {
            return "float"
        }
    }

    class BooleanType internal constructor() : Type() {
        override fun toString(): String {
            return "boolean"
        }
    }

    class ByteType internal constructor() : Type() {
        override fun toString(): String {
            return "byte"
        }
    }

    class ShortType internal constructor() : Type() {
        override fun toString(): String {
            return "short"
        }
    }

    class CharType internal constructor() : Type() {
        override fun toString(): String {
            return "char"
        }
    }

    class MethodType(val argumentTypes: MutableList<Type>, val returnType: Type) {
        constructor(returnType: Type, vararg argumentTypes: Type) : this(
            mutableListOf<Type>(*argumentTypes),
            returnType
        )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MethodType) return false

            return (returnType == other.returnType &&
                    argumentTypes == other.argumentTypes)
        }

        override fun hashCode(): Int {
            return Objects.hash(returnType, argumentTypes)
        }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append("(")
            for (i in argumentTypes.indices) {
                if (i > 0) {
                    sb.append(", ")
                }
                sb.append(argumentTypes[i].toString())
            }
            sb.append("): ")
            sb.append(returnType.toString())
            return sb.toString()
        }
    }
}

fun Type.getKClass(): KClass<*> = when (this) {
    INT_TYPE     -> Int::class
    BYTE_TYPE    -> Byte::class
    SHORT_TYPE   -> Short::class
    LONG_TYPE    -> Long::class
    FLOAT_TYPE   -> Float::class
    DOUBLE_TYPE  -> Double::class
    CHAR_TYPE    -> Char::class
    BOOLEAN_TYPE -> Boolean::class

    INT_TYPE_BOXED      -> Int::class
    BYTE_TYPE_BOXED     -> Byte::class
    SHORT_TYPE_BOXED    -> Short::class
    LONG_TYPE_BOXED     -> Long::class
    FLOAT_TYPE_BOXED    -> Float::class
    DOUBLE_TYPE_BOXED   -> Double::class
    CHAR_TYPE_BOXED     -> Char::class
    BOOLEAN_TYPE_BOXED  -> Boolean::class

    is ArrayType   -> when (elementType) {
        INT_TYPE     -> IntArray::class
        BYTE_TYPE    -> ByteArray::class
        SHORT_TYPE   -> ShortArray::class
        LONG_TYPE    -> LongArray::class
        FLOAT_TYPE   -> FloatArray::class
        DOUBLE_TYPE  -> DoubleArray::class
        CHAR_TYPE    -> CharArray::class
        BOOLEAN_TYPE -> BooleanArray::class
        else         -> Array::class
    }

    is ObjectType -> Any::class

    else -> throw IllegalArgumentException()
}

fun KClass<*>.getType(): Type = when (this) {
    Int::class      -> INT_TYPE
    Byte::class     -> BYTE_TYPE
    Short::class    -> SHORT_TYPE
    Long::class     -> LONG_TYPE
    Float::class    -> FLOAT_TYPE
    Double::class   -> DOUBLE_TYPE
    Char::class     -> CHAR_TYPE
    Boolean::class  -> BOOLEAN_TYPE
    else            -> OBJECT_TYPE
}

fun KClass<*>.getArrayElementType(): Type = when {
    this == IntArray::class     -> INT_TYPE
    this == ByteArray::class    -> BYTE_TYPE
    this == ShortArray::class   -> SHORT_TYPE
    this == LongArray::class    -> LONG_TYPE
    this == FloatArray::class   -> FLOAT_TYPE
    this == DoubleArray::class  -> DOUBLE_TYPE
    this == CharArray::class    -> CHAR_TYPE
    this == BooleanArray::class -> BOOLEAN_TYPE
    this.java.isArray           -> OBJECT_TYPE // We cannot
    // TODO: should we handle atomic arrays?
    else                -> throw IllegalArgumentException("Argument is not array")
}

fun String.toType(): Type {
    return when (this) {
        "I", "java.lang.Integer" -> INT_TYPE
        "J", "java.lang.Long" -> LONG_TYPE
        "D", "java.lang.Double" -> DOUBLE_TYPE
        "F", "java.lang.Float" -> FLOAT_TYPE
        "Z", "java.lang.Boolean" -> BOOLEAN_TYPE
        "B", "java.lang.Byte" -> BYTE_TYPE
        "S", "java.lang.Short" -> SHORT_TYPE
        "C", "java.lang.Character" -> CHAR_TYPE

        else if startsWith("[") -> {
            ArrayType(substring(1).toType())
        }

        else if startsWith("L") && endsWith(";") -> {
            ObjectType(substring(1, length - 1))
        }

        else -> {
            ObjectType(this)
        }
    }
}