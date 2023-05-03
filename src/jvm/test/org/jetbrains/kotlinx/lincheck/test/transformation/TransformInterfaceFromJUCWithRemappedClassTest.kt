/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.test.*
import java.util.concurrent.*

class TransformInterfaceFromJUCWithRemappedClassTest : AbstractLincheckTest() {
    private val q: BlockingQueue<Int> = ArrayBlockingQueue(10)

    init {
        q.add(10)
    }

    @Operation
    fun op() = q.poll(100, TimeUnit.DAYS)

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        threads(1)
        actorsPerThread(1)
        actorsAfter(0)
    }
}
