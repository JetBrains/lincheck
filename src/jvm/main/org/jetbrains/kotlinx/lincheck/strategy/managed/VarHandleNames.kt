/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleMethodType.*
import org.jetbrains.kotlinx.lincheck.util.findFieldNameByOffset
import org.jetbrains.kotlinx.lincheck.util.readFieldViaUnsafe
import sun.misc.Unsafe
import java.lang.invoke.VarHandle
import java.lang.reflect.Field

/**
 * Helper object to provide the field name and the owner of the VarHandle method call.
 */
internal object VarHandleNames {

    private val nameExtractors: List<VarHandleNameExtractor> = listOf(
        instanceNameExtractor(
            "java.lang.invoke.VarHandleReferences\$FieldInstanceReadOnly",
            "java.lang.invoke.VarHandleInts\$FieldInstanceReadOnly",
            "java.lang.invoke.VarHandleDoubles\$FieldInstanceReadOnly",
            "java.lang.invoke.VarHandleLongs\$FieldInstanceReadOnly",
            "java.lang.invoke.VarHandleFloats\$FieldInstanceReadOnly",
            "java.lang.invoke.VarHandleBytes\$FieldInstanceReadOnly",
            "java.lang.invoke.VarHandleShorts\$FieldInstanceReadOnly",
            "java.lang.invoke.VarHandleChars\$FieldInstanceReadOnly",
            "java.lang.invoke.VarHandleBooleans\$FieldInstanceReadOnly"
        ),
        staticNameExtractor(
            "java.lang.invoke.VarHandleReferences\$FieldStaticReadOnly",
            "java.lang.invoke.VarHandleInts\$FieldStaticReadOnly",
            "java.lang.invoke.VarHandleDoubles\$FieldStaticReadOnly",
            "java.lang.invoke.VarHandleLongs\$FieldStaticReadOnly",
            "java.lang.invoke.VarHandleFloats\$FieldStaticReadOnly",
            "java.lang.invoke.VarHandleBytes\$FieldStaticReadOnly",
            "java.lang.invoke.VarHandleShorts\$FieldStaticReadOnly",
            "java.lang.invoke.VarHandleChars\$FieldStaticReadOnly",
            "java.lang.invoke.VarHandleBooleans\$FieldStaticReadOnly",
        ),
        arrayNameExtractor(
            "java.lang.invoke.VarHandleReferences\$Array",
            "java.lang.invoke.VarHandleInts\$Array",
            "java.lang.invoke.VarHandleDoubles\$Array",
            "java.lang.invoke.VarHandleLongs\$Array",
            "java.lang.invoke.VarHandleFloats\$Array",
            "java.lang.invoke.VarHandleBytes\$Array",
            "java.lang.invoke.VarHandleShorts\$Array",
            "java.lang.invoke.VarHandleChars\$Array",
            "java.lang.invoke.VarHandleBooleans\$Array",
        )
    ).flatten()

    internal fun varHandleMethodType(varHandle: VarHandle, parameters: Array<Any?>): VarHandleMethodType = runCatching {
        return nameExtractors.firstOrNull { it.canExtract(varHandle) }?.getMethodType(varHandle, parameters)
            ?: TreatAsDefaultMethod
    }.getOrElse { exception ->
        exception.printStackTrace()
        TreatAsDefaultMethod
    }

    private sealed class VarHandleNameExtractor(varHandleClassName: String) {
        protected val varHandleClass: Class<*> = Class.forName(varHandleClassName)

        fun canExtract(varHandle: VarHandle): Boolean = varHandleClass.isInstance(varHandle)
        abstract fun getMethodType(varHandle: VarHandle, parameters: Array<Any?>): VarHandleMethodType
    }

    /**
     * [VarHandle] that controls instance field contain field with name
     * `fieldOffset` with the offset of the field and `receiverType` with a [Class] of the owner.
     * The strategy is to extract them and find the field name in the owner class using offset.
     */
    private class VarHandleInstanceNameExtractor(
        varHandleClassName: String
    ) : VarHandleNameExtractor(varHandleClassName) {
        private val fieldOffsetField: Field = varHandleClass.getDeclaredField("fieldOffset")
        private val receiverTypeField: Field = varHandleClass.getDeclaredField("receiverType")

        override fun getMethodType(varHandle: VarHandle, parameters: Array<Any?>): VarHandleMethodType {
            val ownerType = readFieldViaUnsafe(varHandle, receiverTypeField, Unsafe::getObject) as Class<*>
            val fieldOffset = readFieldViaUnsafe(varHandle, fieldOffsetField, Unsafe::getLong)
            val fieldName = findFieldNameByOffset(ownerType, fieldOffset) ?: return TreatAsDefaultMethod
            val firstParameter = parameters.firstOrNull() ?: return TreatAsDefaultMethod
            if (!ownerType.isInstance(firstParameter)) return TreatAsDefaultMethod

            return InstanceVarHandleMethod(firstParameter, fieldName, parameters.drop(1))
        }
    }

    /**
     * [VarHandle] that controls static field contain the field with name
     * `fieldOffset` with the offset of the field and the field `base` with a [Class] of the owner.
     * The strategy is to extract them and find the field name in the owner class using offset.
     */
    private class VarHandleStaticNameExtractor(
        varHandleClassName: String
    ) : VarHandleNameExtractor(varHandleClassName) {
        private val fieldOffsetField: Field = varHandleClass.getDeclaredField("fieldOffset")

        private val receiverTypeField: Field = varHandleClass.getDeclaredField("base")

        override fun getMethodType(varHandle: VarHandle, parameters: Array<Any?>): VarHandleMethodType {
            val ownerType = readFieldViaUnsafe(varHandle, receiverTypeField, Unsafe::getObject) as Class<*>
            val fieldOffset = readFieldViaUnsafe(varHandle, fieldOffsetField, Unsafe::getLong)

            val fieldName = findFieldNameByOffset(ownerType, fieldOffset) ?: return TreatAsDefaultMethod

            return StaticVarHandleMethod(ownerType, fieldName, parameters.toList())
        }
    }

    /**
     * [VarHandle] that controls an array receives the array as a first argument and the index as a second,
     * so we just analyze the arguments and return the provided array and the index.
     */
    private class VarHandeArrayNameExtractor(varHandleClassName: String) : VarHandleNameExtractor(varHandleClassName) {
        override fun getMethodType(varHandle: VarHandle, parameters: Array<Any?>): VarHandleMethodType {
            if (parameters.size < 2) return TreatAsDefaultMethod
            val firstParameter = parameters[0] ?:  return TreatAsDefaultMethod
            val index = parameters[1] as? Int ?: return TreatAsDefaultMethod

            return ArrayVarHandleMethod(firstParameter, index, parameters.drop(2))
        }
    }

    @Suppress("SameParameterValue")
    private fun instanceNameExtractor(vararg varHandleClassNames: String) =
        varHandleClassNames.map { VarHandleInstanceNameExtractor(it) }

    @Suppress("SameParameterValue")
    private fun staticNameExtractor(vararg varHandleClassNames: String) =
        varHandleClassNames.map { VarHandleStaticNameExtractor(it) }

    @Suppress("SameParameterValue")
    private fun arrayNameExtractor(vararg varHandleClassNames: String) =
        varHandleClassNames.map { VarHandeArrayNameExtractor(it) }

}

/**
 * Type of the [VarHandle] method call.
 */
internal sealed interface VarHandleMethodType {
    /**
     * Unrecognized [VarHandle] method call so we should present it 'as is'.
     */
    data object TreatAsDefaultMethod : VarHandleMethodType

    /**
     * Array cell access method call.
     */
    data class ArrayVarHandleMethod(val array: Any, val index: Int, val parameters: List<Any?>) : VarHandleMethodType

    /**
     * Method call affecting field [fieldName] of the [owner].
     */
    data class InstanceVarHandleMethod(val owner: Any, val fieldName: String, val parameters: List<Any?>) :
        VarHandleMethodType

    /**
     * Method call affecting static field [fieldName] of the [ownerClass].
     */
    data class StaticVarHandleMethod(val ownerClass: Class<*>, val fieldName: String, val parameters: List<Any?>) :
        VarHandleMethodType
}