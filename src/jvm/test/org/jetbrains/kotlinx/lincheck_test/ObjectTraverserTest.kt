/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.strategy.managed.enumerateReachableObjects
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.LocalObjectManager
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.AtomicArray
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.atomicArrayOfNulls
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * Checks invariants and restrictions on [enumerateReachableObjects] method.
 */
class ObjectTraverserTest {

    private val objectTracker = LocalObjectManager()

    @Before
    fun setUp() {
        objectTracker.reset()
    }

    @Test
    fun `should not traverse class and classLoader recursively while enumerating objects`() {
        val myObject = object : Any() {
            var clazz: Class<*>? = this::class.java
            var classLoader: ClassLoader? = this::class.java.classLoader
            var integer: Int = 10
        }
        val objectEnumeration = objectTracker.enumerateReachableObjects(myObject)

        Assert.assertTrue(objectEnumeration.keys.none { it is Class<*> || it is ClassLoader })
    }

    @Test
    fun `should handle cyclic dependencies`() {
        val objectA = object : Any() {
            var B: Any? = null
        }
        val objectB = object : Any() {
            val A: Any = objectA
        }
        objectA.B = objectB
        val objectEnumeration = objectTracker.enumerateReachableObjects(objectA)

        Assert.assertTrue(objectEnumeration.keys.size == 2 && objectEnumeration.keys.containsAll(listOf(objectA, objectB)))
    }

    @Test
    fun `should traverse reference-array elements for java arrays`() {
        val a = Any()
        val b = Any()
        val c = Any()
        val myObject = object : Any() {
            val array = arrayOf(a, b, c)
        }
        val objectEnumeration = objectTracker.enumerateReachableObjects(myObject)

        Assert.assertTrue(
            objectEnumeration.size == 5 &&
            objectEnumeration.keys.containsAll(
                listOf(myObject, myObject.array, *myObject.array)
            )
        )
    }

    @Test
    fun `should traverse array elements for atomic arrays`() {
        val a = Any()
        val b = Any()
        val c = Any()
        val myObject = object : Any() {
            val array = AtomicReferenceArray(arrayOf(a, b, c))
        }
        val objectEnumeration = objectTracker.enumerateReachableObjects(myObject)

        Assert.assertTrue(
            objectEnumeration.size == 5 &&
            objectEnumeration.keys.containsAll(
                listOf(myObject, myObject.array, myObject.array[0], myObject.array[1], myObject.array[2])
            )
        )
    }

    @Test
    fun `should traverse array elements for atomicfu arrays`() {
        val a = Any()
        val b = Any()
        val c = Any()
        val arraySize = 3
        val myObject = object : Any() {
            val array = AtomicIntArray(arraySize)
            init {
                array[0].value = a
                array[1].value = b
                array[2].value = c
            }
        }
        val objectEnumeration = objectTracker.enumerateReachableObjects(myObject)

        Assert.assertTrue(
            objectEnumeration.size == 5 &&
            objectEnumeration.keys.containsAll(
                listOf(
                    myObject,
                    // atomicfu transformers [are insane and] don't allow to compile direct reference to `myObject.array`
                    myObject.javaClass.getDeclaredField("array").apply { setAccessible(true) }.get(myObject),
                    a, b, c
                )
            )
        )
    }

    @Test
    @Suppress("UNUSED_VARIABLE")
    fun `should jump through atomic refs`() {
        val a = Any()
        val b = Any()
        val myObject = object : Any() {
            val javaRef = AtomicReference<AtomicReference<Any>>(AtomicReference(a))
            val atomicFURef: AtomicRef<AtomicReference<Any>?> = atomic(null)
        }
        myObject.atomicFURef.value = atomic<Any>(b)

        val objectEnumeration = objectTracker.enumerateReachableObjects(myObject)
        Assert.assertTrue(
            objectEnumeration.size == 3 &&
            objectEnumeration.keys.containsAll(
                listOf(myObject, a, b)
            )
        )
    }
}