/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.traceagent.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.Param
import java.util.concurrent.ConcurrentHashMap

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class ConcurrentHashMapTest : AbstractLincheckTest() {
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation
    fun put(@Param(name = "key") key: Int, value: Int) = map.put(key, value)

    @Operation
    operator fun get(@Param(name = "key") key: Int) = map[key]

    @Operation
    fun remove(@Param(name = "key") key: Int) = map.remove(key)

    override fun <O : Options<O, *>> O.customize() {
        // To obtain rare interleaving with `fullAddCount` method
        if (this is ModelCheckingOptions) {
            invocationsPerIteration(if (isInTraceDebuggerMode) 1 else 10000)
            analyzeStdLib(true)
        }
    }
}