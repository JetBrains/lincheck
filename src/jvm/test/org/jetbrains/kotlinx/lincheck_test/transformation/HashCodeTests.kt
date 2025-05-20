/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.junit.*

/**
 * Checks that [Object.hashCode] is replaced with a deterministic
 * implementations in the model checking mode.
 */
@ModelCheckingCTest(iterations = 50, invocationsPerIteration = 1000)
class HashCodeStubTest {
    @Volatile
    private var a: Any = Any()

    @Operation
    fun operation() {
        val newA = Any()
        if (newA.hashCode() % 3 == 2) {
            // just add some code locations
            a = Any()
            a = Any()
        }
        a = newA // to prevent stack allocation
    }

    @Test
    fun test() {
        LinChecker.check(this::class.java)
    }
}

class HashCodeCallSensitivityTest() {
    @Operation
    fun operation() {
        val a: Any = 1
        val b = 1
        check(a.hashCode() == b.hashCode())
    }

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}

class IdentityHashCodeSupportedTest() {
    @Operation
    fun operation() {
        val obj = Any()
        // Check that System.identityHashCode(..) is analyzed
        check(obj.hashCode() == System.identityHashCode(obj))
        // Check that identityHashCode stays the same
        check(System.identityHashCode(obj) == System.identityHashCode(obj))
    }

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}

class IdentityHashCodeOnNullTest {
    @Operation
    fun hashCodeOnObject() {
        val o: Any? = null
        System.identityHashCode(o)
    }

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}
