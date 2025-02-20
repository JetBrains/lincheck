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

import junit.framework.TestCase.fail
import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Test

abstract class BaseRunCoroutineTests(
    private val shouldFail: Boolean = false,
    private val iterations: Int = -1,
) {
    abstract fun block()
    
    @OptIn(ExperimentalModelCheckingAPI::class)
    @Test
    fun executeCoroutineTest() {
        val result = runCatching {
            if (iterations != -1) runConcurrentTest(iterations) { block() }
            else runConcurrentTest { block() }
        }
        if (result.isFailure != shouldFail) {
            System.err.println(if (shouldFail) "Should've failed but succeeded" else "Should've succeeded but failed")
            throw result.exceptionOrNull()!!
        }
    }
}