/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc

import kotlin.concurrent.thread
import org.junit.Test
import org.junit.Ignore

class HangTest {

    fun hang() {
        val t1 = thread {
            while (true) { }
        }
        val t2 = thread {
            while (true) { }
        }
        t1.join()
        t2.join()
    }

    @Ignore
    @Test(timeout = TIMEOUT)
    fun testHang() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::hang,
        outcomes = setOf<Unit>(),
    )

}