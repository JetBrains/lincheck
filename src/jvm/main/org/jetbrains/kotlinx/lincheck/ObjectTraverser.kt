/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectLabelFactory.getObjectNumber
import org.jetbrains.kotlinx.lincheck.util.isAtomic
import org.jetbrains.kotlinx.lincheck.util.isAtomicFU
import org.jetbrains.kotlinx.lincheck.util.readField
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.*
import kotlin.coroutines.Continuation

/**
 * Traverses an object to enumerate it and all nested objects.
 * Enumeration is required for the Plugin as we want to see on the diagram if some object was replaced by a new one.
 * Uses the same a numeration map as TraceReporter via [getObjectNumber] method, so objects have the
 * same numbers, as they have in the trace.
 */
internal fun enumerateObjects(obj: Any): Map<Any, Int> {
    val objectNumberMap = hashMapOf<Any, Int>()
    enumerateObjects(obj, objectNumberMap)
    return objectNumberMap
}

/**
 * Recursively traverses an object to enumerate it and all nested objects.
 *
 * @param obj object to traverse
 * @param objectNumberMap result enumeration map
 */
private fun enumerateObjects(obj: Any, objectNumberMap: MutableMap<Any, Int>) {
    println("Traversing object: $obj")
    if (obj is Class<*> || obj is ClassLoader) return
    objectNumberMap[obj] = getObjectNumber(obj.javaClass, obj)

    traverseObjectHierarchy(obj) { _, f, value ->
        if (
            Modifier.isStatic(f.modifiers) ||
            f.isEnumConstant ||
            f.name == "serialVersionUID"
        ) return@traverseObjectHierarchy null

        try {
            if (value == null || value is Class<*> || value is ClassLoader) return@traverseObjectHierarchy null
            objectNumberMap[value] = getObjectNumber(value.javaClass, value)

            var jumpValue: Any? = value

            // we jump through most of the atomic classes
            if (isAtomic(jumpValue)) {
                jumpValue = jumpValue!!.javaClass.getDeclaredMethod("get").invoke(jumpValue)
            }

            if (isAtomicFU(jumpValue)) {
                val readNextJumpValueByFieldName = { fieldName: String ->
                    readField(jumpValue, jumpValue!!.javaClass.getDeclaredField(fieldName))
                }

                if (jumpValue is kotlinx.atomicfu.AtomicRef<*>) {
                    jumpValue = readNextJumpValueByFieldName("value")
                }

                if (isAtomicFU(jumpValue)) {
                    jumpValue =
                        if (jumpValue is kotlinx.atomicfu.AtomicBoolean) readNextJumpValueByFieldName("_value")
                        else readNextJumpValueByFieldName("value")
                }
            }

//            if (jumpValue?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicRef") {
//                jumpValue = readField(jumpValue, jumpValue.javaClass.getDeclaredField("value"))
//            }
//            if (jumpValue?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicInt") {
//                jumpValue = readField(jumpValue, jumpValue.javaClass.getDeclaredField("value"))
//            }
//            if (jumpValue?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicLong") {
//                jumpValue = readField(jumpValue, jumpValue.javaClass.getDeclaredField("value"))
//            }
//            if (jumpValue?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicBoolean") {
//                jumpValue = readField(jumpValue, jumpValue.javaClass.getDeclaredField("_value"))
//            }

//            if (jumpValue is AtomicIntegerArray) {
//                jumpValue = (0 until jumpValue.length()).map { (jumpValue as AtomicIntegerArray).get(it) }.toIntArray()
//            }
            if (jumpValue is AtomicReferenceArray<*>) {
                jumpValue = (0 until jumpValue.length()).map { (jumpValue as AtomicReferenceArray<*>).get(it) }.toTypedArray()
            }

            if (
                jumpValue is AtomicReferenceFieldUpdater<*, *> ||
                jumpValue is AtomicIntegerFieldUpdater<*> ||
                jumpValue is AtomicLongFieldUpdater<*>
            ) {
                return@traverseObjectHierarchy null
            }

            return@traverseObjectHierarchy if (shouldAnalyseObjectRecursively(jumpValue, objectNumberMap)) jumpValue else null
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        return@traverseObjectHierarchy null
    }
}

/**
 * Determines should we dig recursively into this object's fields.
 */
private fun shouldAnalyseObjectRecursively(obj: Any?, objectNumberMap: MutableMap<Any, Int>): Boolean {
    if (obj == null || obj.javaClass.isImmutableWithNiceToString)
        return false

    if (obj is CharSequence) {
        return false
    }
    if (obj is Continuation<*>) {
        objectNumberMap[obj] = getObjectNumber(obj.javaClass, obj)
        return false
    }
    return true
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private val Class<out Any>.isImmutableWithNiceToString
    get() = this.canonicalName in listOf(
        java.lang.Integer::class.java,
        java.lang.Long::class.java,
        java.lang.Short::class.java,
        java.lang.Double::class.java,
        java.lang.Float::class.java,
        java.lang.Character::class.java,
        java.lang.Byte::class.java,
        java.lang.Boolean::class.java,
        BigInteger::class.java,
        BigDecimal::class.java,
        kotlinx.coroutines.internal.Symbol::class.java,
    ).map { it.canonicalName } || this.isEnum
