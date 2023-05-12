/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.test.verifier

import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.annotations.Operation
import org.jetbrains.lincheck.strategy.*
import org.jetbrains.lincheck.test.*
import org.jetbrains.lincheck.verifier.*
import java.util.concurrent.atomic.*

class SequentialSpecificationTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private val c = AtomicInteger()

    @Operation
    fun set(value: Int) = c.set(value)

    @Operation
    fun get() = c.get() + 1

    override fun <O : Options<O, *>> O.customize() {
        sequentialSpecification(CorrectCounter::class.java)
    }
}

class CorrectCounter: VerifierState() {
    private var c = 0
    fun set(value: Int) { c = value }
    fun get(): Int = c
    override fun extractState() = c
}
