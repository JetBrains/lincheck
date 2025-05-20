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

import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck_test.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import java.util.*

@Param(name = "key", gen = IntGen::class)
class HashMapTest : AbstractLincheckTest(IncorrectResultsFailure::class, UnexpectedExceptionFailure::class) {
    private val m = HashMap<Int, Int>()

    @Operation
    fun put(key: Int, @Param(name = "key") value: Int): Int? = m.put(key, value)

    @Operation
    operator fun get(@Param(name = "key") key: Int?): Int? = m[key]
}

