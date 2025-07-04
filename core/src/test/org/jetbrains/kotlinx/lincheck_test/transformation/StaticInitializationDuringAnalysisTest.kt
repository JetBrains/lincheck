/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class StaticInitializationDuringAnalysisTest {
    val counter = AtomicInteger()

    @Operation
    fun incrementAndGet(): Int {
        LoadedDuringAnalysisClass.x
        return counter.incrementAndGet()
    }

    @Test
    fun test() = ModelCheckingOptions().iterations(1).check(this::class)
}

private object LoadedDuringAnalysisClass {
    @JvmStatic
    var x = ArrayList<Int>().run {
        repeat(10000) { add(it) }
    }
}