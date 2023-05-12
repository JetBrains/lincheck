/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.test.runner

import kotlinx.coroutines.*
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.annotations.Operation
import org.jetbrains.lincheck.test.AbstractLincheckTest
import kotlin.coroutines.*

class RunBlockingTest : AbstractLincheckTest() {
    @Operation
    fun foo(x: Int) = runBlocking<Int> {
        suspendCoroutine sc@ { cont ->
            cont.resume(x + 1000_000)
        }
    }

    @Operation
    fun bar(x: Int) = runBlocking<Int> {
        suspendCancellableCoroutine sc@ { cont ->
            cont.resume(x + 1000)
        }
    }

    override fun extractState() = Unit

    override fun <O : Options<O, *>> O.customize() {
        minimizeFailedScenario(false)
        iterations(1)
    }
}
