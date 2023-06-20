/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.InternalLincheckTestUnexpectedException
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.coroutines.*

class UnexpectedExceptionTest : AbstractLincheckTest(UnexpectedExceptionFailure::class) {
    private var canEnterForbiddenSection = false

    @Operation
    fun operation1() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
    }

    @Operation
    fun operation2() {
        if (canEnterForbiddenSection) throw InternalLincheckTestUnexpectedException
    }

    override fun extractState(): Any = canEnterForbiddenSection
}

class CoroutineResumedWithUnexpectedExceptionTest : AbstractLincheckTest(UnexpectedExceptionFailure::class) {
    @InternalCoroutinesApi
    @Operation
    suspend fun operation() {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.resumeWithException(InternalLincheckTestUnexpectedException)
        }
    }

    override fun extractState(): Any = 0 // constant state
}
