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

import org.jetbrains.kotlinx.lincheck.util.*
import kotlin.coroutines.Continuation
import java.util.*

/**
 * Helps to assign number to an object and to create its beautiful representation to provide to the trace.
 */
object ObjectLabelFactory {

    private val objectNumeration = Collections.synchronizedMap(WeakHashMap<Class<*>, MutableMap<Any, Int>>())

    internal fun adornedStringRepresentation(any: Any?): String {
        if (any == null) return "null"
        // Chars and strings are wrapped in quotes.
        if (any is Char) return "\'$any\'"
        if (any is String) return "\"$any\""
        // Primitive types (and several others) are immutable and
        // have trivial `toString` implementation, which is used here.
        if (any.isImmutable)
            return any.toString()
        // For enum types, we can always display their name.
        if (any.javaClass.isEnum) {
            return (any as Enum<*>).name
        }
        // simplified representation for Continuations
        // (we usually do not really care about details).
        if (any is Continuation<*>)
            return "<cont>"
        // Instead of java.util.HashMap$Node@3e2a56 show Node@1.
        // It is better not to use `toString` in general since
        // we usually care about references to certain objects,
        // not about the content inside them.
        return getObjectName(any)
    }

    internal fun getObjectNumber(clazz: Class<*>, obj: Any): Int = objectNumeration
        .computeIfAbsent(clazz) { IdentityHashMap() }
        .computeIfAbsent(obj) { 1 + objectNumeration[clazz]!!.size }

    internal fun cleanObjectNumeration() {
        objectNumeration.clear()
    }

    private val Class<*>.simpleNameForAnonymous: String
        get() {
            // Split by the package separator and return the result if this is not an inner class.
            val withoutPackage = name.substringAfterLast('.')
            if (!withoutPackage.contains("$")) return withoutPackage
            // Extract the last named inner class followed by any "$<number>" patterns using regex.
            val regex = """(.*\$)?([^\$.\d]+(\$\d+)*)""".toRegex()
            val matchResult = regex.matchEntire(withoutPackage)
            return matchResult?.groups?.get(2)?.value ?: withoutPackage
        }

    internal fun getObjectName(obj: Any): String {
        if (obj is Thread) {
            return "Thread#${getObjectNumber(Thread::class.java, obj)}"
        }
        if (obj.javaClass.isAnonymousClass) {
            return obj.javaClass.simpleNameForAnonymous
        }
        return objectName(obj) + "#" + getObjectNumber(obj.javaClass, obj)
    }

    private fun objectName(obj: Any): String {
        return when (obj) {
            is IntArray -> "IntArray"
            is ShortArray -> "ShortArray"
            is CharArray -> "CharArray"
            is ByteArray -> "ByteArray"
            is BooleanArray -> "BooleanArray"
            is DoubleArray -> "DoubleArray"
            is FloatArray -> "FloatArray"
            is LongArray -> "LongArray"
            is Array<*> -> "Array<${obj.javaClass.componentType.simpleName}>"
            else -> obj.javaClass.simpleName
        }
    }

}