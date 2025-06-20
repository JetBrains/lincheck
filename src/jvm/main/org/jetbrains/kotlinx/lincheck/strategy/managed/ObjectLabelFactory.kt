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

import org.jetbrains.kotlinx.lincheck.transformation.isJavaLambdaClass
import org.jetbrains.kotlinx.lincheck.util.*
import kotlin.coroutines.Continuation
import java.util.*

/**
 * Helps to assign number to an object and to create its beautiful representation to provide to the trace.
 */
object ObjectLabelFactory {

    var isGPMCMode = true
    internal val objectNumeration = Collections.synchronizedMap(WeakHashMap<Class<*>, MutableMap<Any, Int>>())

    internal fun adornedStringRepresentation(any: Any?): String {
        if (any == null) return "null"
        // Chars and strings are wrapped in quotes.
        if (any is Char) return "\'$any\'"
        if (any is String) return "\"$any\""
        // Simple representation of Unit
        if (any is Unit) return "Unit"
        // Primitive types (and several others) are immutable and
        // have trivial `toString` implementation, which is used here.
        if (any.isImmutable)
            return any.toString()
        // For enum types, we can always display their name.
        if (any.javaClass.isEnum) {
            return (any as Enum<*>).name
        }
        if (any is Pair<*, *>) {
            return "(${adornedStringRepresentation(any.first)}, ${adornedStringRepresentation(any.second)})"
        }
        if (any is Triple<*, *, *>) {
            return "(${adornedStringRepresentation(any.first)}, ${adornedStringRepresentation(any.second)}, ${adornedStringRepresentation(any.third)})"
        }
        // Instead of java.util.HashMap$Node@3e2a56 show Node@1.
        // It is better not to use `toString` in general since
        // we usually care about references to certain objects,
        // not about the content inside them.
        return getObjectName(any)
    }

    internal fun getObjectNumber(clazz: Class<*>, obj: Any): Int {
        val offset = if (clazz == Thread::class.java && isGPMCMode) -1 else 0
        return objectNumeration
            .computeIfAbsent(clazz) { IdentityHashMap() }
            .computeIfAbsent(obj) { 1 + objectNumeration[clazz]!!.size + offset }
    }

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
        if (obj is Continuation<*>) {
            return "Continuation#${getObjectNumber(Continuation::class.java, obj)}"
        }
        if (isJavaLambdaClass(obj.javaClass.name)) {
            return "Lambda#${getObjectNumber(Lambda::class.java, obj)}"
        }
        runCatching {
            if (obj.javaClass.isAnonymousClass) {
                return obj.javaClass.simpleNameForAnonymous
            }
        }
        val objectName = runCatching {
            objectName(obj) + "#" + getObjectNumber(obj.javaClass, obj)
        }
        // There is a Kotlin compiler bug that leads to exception
        // `java.lang.InternalError: Malformed class name`
        // when trying to query for a class name of an anonymous class on JDK 8:
        // - https://youtrack.jetbrains.com/issue/KT-16727/
        // in such a case we fall back to returning `<unknown>` class name.
        .getOrElse {
            "<unknown>"
        }
        return objectName
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

/**
 * Since lambdas do not have a specific type, this class is used as key for [ObjectLabelFactory.getObjectNumber].
 */
private class Lambda