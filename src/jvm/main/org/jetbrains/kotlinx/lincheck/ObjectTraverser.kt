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

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.REMAPPED_PACKAGE_CANONICAL_NAME
import org.jetbrains.kotlinx.lincheck.strategy.managed.getObjectNumber
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.Continuation

fun traverseTestObject(obj: Any): Map<Any, Int> {
    val result = hashMapOf<Any, Int>()
    traverseTestObject(obj, Collections.newSetFromMap(IdentityHashMap()), result)

    return result
}

private fun traverseTestObject(obj: Any, visualized: MutableSet<Any>, result: MutableMap<Any, Int>) {
    if (!visualized.add(obj)) return
    val objectNumber = getObjectNumber(obj.javaClass, obj)
    result[obj] = objectNumber

    var clazz: Class<*>? = obj.javaClass
    if (clazz!!.isArray) {
        if (obj is Array<*>) {
            obj.forEach { element -> element?.let { traverseTestObject(it, visualized, result) } }
        }
        return
    }
    while (clazz != null) {
        clazz.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) &&
                    !f.isEnumConstant &&
                    f.name != "serialVersionUID"
        }.forEach { f ->
            try {
                try {
                    f.isAccessible = true
                } catch (_: Throwable) {
                    return@forEach
                }
                var value: Any? = f.get(obj)

                if (isAtomic(value)) {
                    value = value!!.javaClass.getDeclaredMethod("get").invoke(value)
                }

                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicRef") value =
                    value.javaClass.getDeclaredField("value").apply { isAccessible = true }.get(value)
                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicInt") value =
                    value.javaClass.getDeclaredField("value").apply { isAccessible = true }.get(value)
                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicLong") value =
                    value.javaClass.getDeclaredField("value").apply { isAccessible = true }.get(value)
                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicBoolean") value =
                    value.javaClass.getDeclaredField("_value").apply { isAccessible = true }.get(value)

                if (value is AtomicIntegerArray) value =
                    (0..value.length()).map { (value as AtomicIntegerArray).get(it) }.toIntArray()
                if (value is AtomicReferenceArray<*>) value =
                    (0..value.length()).map { (value as AtomicReferenceArray<*>).get(it) }.toTypedArray()

                if (value?.javaClass?.canonicalName?.startsWith("java.lang.invoke.") ?: false) {
                    // Ignore
                } else if (value is AtomicReferenceFieldUpdater<*, *> || value is AtomicIntegerFieldUpdater<*> || value is AtomicLongFieldUpdater<*>) {
                    // Ignore
                } else if (value is ReentrantLock) {
                    // Ignore
                } else {
                    if (shouldProcessFurther(value, result)) {
                        if (value != null) {
                            traverseTestObject(value, visualized, result)
                        }
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                // Ignore
            }
        }
        clazz = clazz.superclass
    }
}

private fun isAtomic(value: Any?): Boolean {
    val atomic = value?.javaClass?.canonicalName?.let {
        it == REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.concurrent.atomic.AtomicInteger" ||
                it == REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.concurrent.atomic.AtomicLong" ||
                it == REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.concurrent.atomic.AtomicReference" ||
                it == REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.concurrent.atomic.AtomicBoolean"
    } ?: false
    return atomic
}

// Try to construct a string representation
private fun shouldProcessFurther(obj: Any?, result: MutableMap<Any, Int>): Boolean {
    if (obj == null || obj.javaClass.isImmutableWithNiceToString)
        return false
//    getObjectNumber(obj.javaClass, obj)

    if (obj is CharSequence) {
        return false
    }
    if (obj is Continuation<*>) {
        result[obj] = getObjectNumber(obj.javaClass, obj)
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
