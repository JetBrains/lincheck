/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.traceagent.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck_test.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import java.util.concurrent.*

@Param(name = "value", gen = IntGen::class, conf = "1:5")
class SkipListMapTest : AbstractLincheckTest() {
    override fun <O : Options<O, *>> O.customize() {
        if (isInTraceDebuggerMode && this is ModelCheckingOptions) {
            invocationsPerIteration(1)
        }
        if (this is ModelCheckingOptions) analyzeStdLib(true)
    }

    private val skiplistMap = ConcurrentSkipListMap<Int, Int>()

    @Operation
    fun put(key: Int, value: Int) = skiplistMap.put(key, value)

    @Operation
    fun get(key: Int) = skiplistMap.get(key)

    @Operation
    fun containsKey(key: Int) = skiplistMap.containsKey(key)

    @Operation
    fun remove(key: Int) = skiplistMap.remove(key)
}
