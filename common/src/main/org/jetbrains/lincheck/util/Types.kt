/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass


/**
 * Extension property to determine if an object is of an immutable type.
 */
internal val Any?.isImmutable get() = when {
    this.isPrimitive        -> true
    this.isUnit             -> true
    this is String          -> true
    this is BigInteger      -> true
    this is BigDecimal      -> true
    else                    -> false
}

/**
 * Extension property to determine if an object is of a primitive type.
 */
internal val Any?.isPrimitive get() = when (this) {
    is Boolean, is Int, is Short, is Long, is Double, is Float, is Char, is Byte -> true
    else -> false
}

/**
 * Extension property to determine if an object is the Kotlin [Unit] singleton.
 *
 * Matched by class name, not with `is Unit`:
 * the agent payload loads its own `kotlin-stdlib` through an isolated class loader,
 * so the traced application's `kotlin.Unit` is a different class than the javaagent's.
 */
internal val Any?.isUnit: Boolean get() =
    this?.javaClass?.name == KOTLIN_UNIT_CLASS_NAME

private const val KOTLIN_UNIT_CLASS_NAME = "kotlin.Unit"

/**
 * Extension property to determine if an object is a Kotlin [KClass] instance.
 *
 * Matched by name, not with `is KClass<*>`:
 * the javaagent payload loads its own `kotlin-stdlib` through an isolated class loader,
 * so the traced application's `KClass` implements a different `kotlin.reflect.KClass` than the javaagent's.
 */
internal val Any?.isKClass: Boolean get() =
    this != null && KotlinClassSupport.isKClass(javaClass)

/**
 * The JVM binary name of the class this Kotlin [KClass] refers to.
 *
 * Not the Kotlin qualified name: nested and mapped types are named by their JVM name,
 * so `kotlin.String::class` yields `java.lang.String`.
 *
 * @throws IllegalArgumentException if the receiver is not a [KClass]; guard with [isKClass].
 */
internal val Any.kClassReferencedName: String get() {
    require(isKClass) { "Not a KClass instance: ${javaClass.name}" }
    return (KotlinClassSupport.jClassGetter(javaClass).invoke(this) as Class<*>).name
}

/**
 * Caches the per-class reflection needed to recognize
 * a Kotlin `KClass` across the javaagent class loader isolation boundary.
 */
private object KotlinClassSupport {
    private const val KCLASS_CLASS_NAME = "kotlin.reflect.KClass"
    private const val J_CLASS_GETTER_NAME = "getJClass"

    // Resolved through the value's *own* class loader,
    // so the application's and the javaagent's `KClass` are both recognized,
    // and `isAssignableFrom` covers indirect implementations.
    private val isKClassCache = object : ClassValue<Boolean>() {
        override fun computeValue(type: Class<*>): Boolean = try {
            Class.forName(KCLASS_CLASS_NAME, false, type.classLoader).isAssignableFrom(type)
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    private val jClassGetterCache = object : ClassValue<Method>() {
        override fun computeValue(type: Class<*>): Method = type.getMethod(J_CLASS_GETTER_NAME)
    }

    fun isKClass(type: Class<*>): Boolean = isKClassCache.get(type)

    fun jClassGetter(type: Class<*>): Method = jClassGetterCache.get(type)
}
