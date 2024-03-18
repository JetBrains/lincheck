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
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.*
import java.util.*
import java.util.concurrent.*

class JavaRandomTest : AbstractRandomTest() {
    override fun nextInt() = Random().nextInt()
}

class JavaThreadLocalRandomTest : AbstractRandomTest() {
    override fun nextInt() = ThreadLocalRandom.current().nextInt()
}

class JavaThreadLocalRandomTest2 : AbstractRandomTest() {
    override fun nextInt() = ThreadLocalRandom.current().nextInt(10, 100)
}

class KotlinRandomTest : AbstractRandomTest() {
    override fun nextInt() = kotlin.random.Random.nextInt()
}

abstract class AbstractRandomTest {
    var a: Int = 0

    abstract fun nextInt(): Int

    @Operation
    fun operation() {
        a++
        if (nextInt() % 2 == 0) {
            a++
        }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .iterations(1)
        .actorsBefore(0)
        .actorsAfter(0)
        .threads(3)
        .actorsPerThread(1)
        .check(this::class)
}
