/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.datastructures.linearizable

import org.jetbrains.kotlinx.lincheck.transformation.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.Param
import java.util.concurrent.ConcurrentSkipListMap

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
