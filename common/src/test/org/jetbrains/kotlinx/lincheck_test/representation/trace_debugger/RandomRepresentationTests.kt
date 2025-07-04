/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation.trace_debugger

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.transformation.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck_test.representation.BaseTraceRepresentationTest
import org.jetbrains.kotlinx.lincheck_test.trace_debugger.NotRandom
import org.junit.Assume.assumeTrue
import org.junit.Before

class SuccessfulCustomRandomCallRepresentationTest : BaseTraceRepresentationTest("trace_debugger/successful_nextInt") {
    @Before
    fun setUp() {
        assumeTrue(isInTraceDebuggerMode)
    }

    @Operation
    override fun operation() {
        NotRandom.nextInt(0, 1)
    }
}

class FailingCustomRandomCallRepresentationTest : BaseTraceRepresentationTest("trace_debugger/failing_nextInt") {
    @Before
    fun setUp() {
        assumeTrue(isInTraceDebuggerMode)
    }

    @Operation
    override fun operation() {
        NotRandom.nextInt(1, 1)
    }
}

class CustomRandomBytesCallRepresentationTest : BaseTraceRepresentationTest("trace_debugger/random_bytes") {
    @Before
    fun setUp() {
        assumeTrue(isInTraceDebuggerMode)
    }

    @Operation
    override fun operation() {
        NotRandom.nextBytes(ByteArray(10))
    }
}
