/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CoroutineJoinTest : FixedThreadPoolCoroutineTest() {

    @Test
    fun testJoin() = executeCoroutineTest { dispatcher ->
        runBlocking(dispatcher) {
            val flag = AtomicBoolean(false)
            val c = launch(dispatcher) {
                flag.set(true)
            }
            c.join()
            check(flag.get())
        }
    }
}