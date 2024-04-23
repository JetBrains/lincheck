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

import org.jetbrains.kotlinx.lincheck.strategy.managed.getObjectNumber
import org.jetbrains.kotlinx.lincheck.util.readFieldViaUnsafe
import sun.misc.Unsafe
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*
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
    enumerateObjects(obj, Collections.newSetFromMap(IdentityHashMap()), objectNumberMap)

    return objectNumberMap
}

/**
 * Recursively traverses an object to enumerate it and all nested objects.
 *
 * @param obj object to traverse
 * @param processedObjects a set of already processed objects. Required in case of cyclic references.
 * @param objectNumberMap result enumeration map
 */
private fun enumerateObjects(obj: Any, processedObjects: MutableSet<Any>, objectNumberMap: MutableMap<Any, Int>) {
    if (!processedObjects.add(obj)) return
    val objectNumber = getObjectNumber(obj.javaClass, obj)
    objectNumberMap[obj] = objectNumber

    var clazz: Class<*>? = obj.javaClass
    if (clazz!!.isArray) {
        if (obj is Array<*>) {
            obj.forEach { element -> element?.let { enumerateObjects(it, processedObjects, objectNumberMap) } }
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
                var value: Any? = readField(obj, f)

                if (isAtomic(value)) {
                    value = value!!.javaClass.getDeclaredMethod("get").invoke(value)
                }

                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicRef") {
                    value = readField(value, value.javaClass.getDeclaredField("value"))
                }
                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicInt") {
                    value = readField(value, value.javaClass.getDeclaredField("value"))
                }
                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicLong") {
                    value = readField(value, value.javaClass.getDeclaredField("value"))
                }
                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicBoolean") {
                    value = readField(value, value.javaClass.getDeclaredField("_value"))
                }

                if (value is AtomicIntegerArray) {
                    value = (0..value.length()).map { (value as AtomicIntegerArray).get(it) }.toIntArray()
                }
                if (value is AtomicReferenceArray<*>) {
                    value = (0..value.length()).map { (value as AtomicReferenceArray<*>).get(it) }.toTypedArray()
                }

                if (value is AtomicReferenceFieldUpdater<*, *> || value is AtomicIntegerFieldUpdater<*> || value is AtomicLongFieldUpdater<*>) {
                    // Ignore
                } else {
                    if (shouldAnalyseObjectRecursively(value, objectNumberMap)) {
                        if (value != null) {
                            enumerateObjects(value, processedObjects, objectNumberMap)
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

private fun readField(obj: Any?, field: Field): Any? {
    if (!field.type.isPrimitive) {
        return readFieldViaUnsafe(obj, field, Unsafe::getObject)
    }
    return when (field.type) {
        Boolean::class.javaPrimitiveType    -> readFieldViaUnsafe(obj, field, Unsafe::getBoolean)
        Byte::class.javaPrimitiveType       -> readFieldViaUnsafe(obj, field, Unsafe::getByte)
        Char::class.javaPrimitiveType       -> readFieldViaUnsafe(obj, field, Unsafe::getChar)
        Short::class.javaPrimitiveType      -> readFieldViaUnsafe(obj, field, Unsafe::getShort)
        Int::class.javaPrimitiveType        -> readFieldViaUnsafe(obj, field, Unsafe::getInt)
        Long::class.javaPrimitiveType       -> readFieldViaUnsafe(obj, field, Unsafe::getLong)
        Double::class.javaPrimitiveType     -> readFieldViaUnsafe(obj, field, Unsafe::getDouble)
        Float::class.javaPrimitiveType      -> readFieldViaUnsafe(obj, field, Unsafe::getFloat)
        else                                -> error("No more types expected")
    }
}

private fun isAtomic(value: Any?): Boolean {
    if (value == null) return false
    return value.javaClass.canonicalName.let {
        it == "java.util.concurrent.atomic.AtomicInteger" ||
        it == "java.util.concurrent.atomic.AtomicLong" ||
        it == "java.util.concurrent.atomic.AtomicReference" ||
        it == "java.util.concurrent.atomic.AtomicBoolean"
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
