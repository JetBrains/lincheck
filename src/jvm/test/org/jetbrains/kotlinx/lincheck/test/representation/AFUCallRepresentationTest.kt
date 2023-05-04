/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.representation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.test.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.concurrent.atomic.*

/**
 * This test checks that AFU calls captured in an incorrect interleaving have proper representation.
 * Instead of `compareAndSet(object, 1, 2)` representation should be `fieldName.compareAndSet(1, 2)`,
 * where `fieldName` is the parameter in constructor for the AFU.
 */
class AFUCallRepresentationTest : VerifierState() {
    @Volatile
    private var counter = 0
    private val afu = AtomicIntegerFieldUpdater.newUpdater(AFUCallRepresentationTest::class.java, "counter")

    @Operation
    fun operation(): Int {
        var value = 0
        // first inc
        do {
            value = afu.get(this)
        } while (!afu.compareAndSet(this, value, value + 1))
        // second inc
        do {
            value = afu.get(this)
        } while (!afu.compareAndSet(this, value, value + 1))
        return value + 1
    }

    override fun extractState(): Any = counter

    @Test
    fun test() {
        val options = ModelCheckingOptions()
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val log = StringBuilder().appendFailure(failure).toString()
        check("counter.compareAndSet(0,1)" in log)
        checkTraceHasNoLincheckEvents(log)
    }
}
