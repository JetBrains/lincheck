/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
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
    @Suppress("DEPRECATION_ERROR")
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
