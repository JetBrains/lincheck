/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.tracedata

import java.util.*
import kotlin.math.max

object Types {
    private fun convertAsmTypeName(className: String): Type {
        when (className) {
            "V" -> return VOID_TYPE
            "I" -> return INT_TYPE
            "J" -> return LONG_TYPE
            "D" -> return DOUBLE_TYPE
            "F" -> return FLOAT_TYPE
            "Z" -> return BOOLEAN_TYPE
            "B" -> return BYTE_TYPE
            "S" -> return SHORT_TYPE
            "C" -> return CHAR_TYPE
            else -> if (className.startsWith("[")) {
                return ArrayType(convertAsmTypeName(className.substring(1)))
            } else {
                require(!(!className.startsWith("L") || !className.endsWith(";"))) { "Invalid type name: $className" }
                return ObjectType(className.substring(1, className.length - 1).replace('/', '.'))
            }
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
        return (type is IntType ||
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

    class VoidType : Type() {
        override fun toString(): String {
            return "void"
        }
    }

    class IntType : Type() {
        override fun toString(): String {
            return "int"
        }
    }

    class LongType : Type() {
        override fun toString(): String {
            return "long"
        }
    }

    class DoubleType : Type() {
        override fun toString(): String {
            return "double"
        }
    }

    class FloatType : Type() {
        override fun toString(): String {
            return "float"
        }
    }

    class BooleanType : Type() {
        override fun toString(): String {
            return "boolean"
        }
    }

    class ByteType : Type() {
        override fun toString(): String {
            return "byte"
        }
    }

    class ShortType : Type() {
        override fun toString(): String {
            return "short"
        }
    }

    class CharType : Type() {
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
