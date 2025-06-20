/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.runner

import org.jetbrains.kotlinx.lincheck.Suspended
import org.jetbrains.kotlinx.lincheck.ValueResult
import org.jetbrains.kotlinx.lincheck_test.verifier.*
import org.jetbrains.lincheck.datastructures.verifier.LinearizabilityVerifier
import org.junit.Test
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import org.jetbrains.lincheck.datastructures.actor

// Test for early scenario verification completion: follow-up part resumes suspended operation.
class ResumingFollowUpTest {
    private var fooCont: Continuation<Int>? = null
    private var bazCont: Continuation<Int>? = null

    suspend fun foo(): String {
        suspendCoroutine<Int> {
            fooCont = it
            COROUTINE_SUSPENDED
        }
        // follow-up part:
        return if (bazCont == null) {
            "afterBar() function was not called yet"
        } else {
            bazCont!!.resumeWith(Result.success(42))
            "OK"
        }
    }

    fun bar(value: Int): Boolean {
        if (fooCont == null) return false
        fooCont!!.resumeWith(Result.success(value))
        return true
    }

    suspend fun baz() = suspendCoroutine<Int> {
        bazCont = it
        COROUTINE_SUSPENDED
    }

    private val f = ResumingFollowUpTest::foo
    private val b = ResumingFollowUpTest::bar
    private val afterB = ResumingFollowUpTest::baz

    @Test
    fun testEarlyScenarioCompletion() {
        verify(ResumingFollowUpTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(f), ValueResult("OK"))
                }
                thread {
                    operation(actor(b, 1), ValueResult(true))
                    operation(actor(afterB), Suspended) // should be S + 42
                }
            }
        }, false)
    }
}
