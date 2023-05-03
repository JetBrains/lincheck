/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.runner

import org.jetbrains.kotlinx.lincheck.Suspended
import org.jetbrains.kotlinx.lincheck.ValueResult
import org.jetbrains.kotlinx.lincheck.test.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier
import org.junit.Test
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import org.jetbrains.kotlinx.lincheck.actor
import org.jetbrains.kotlinx.lincheck.dsl.actor

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
                    operation(actor(f), ValueResult("OK", wasSuspended = true))
                }
                thread {
                    operation(actor(b, 1), ValueResult(true))
                    operation(actor(afterB), Suspended) // should be S + 42
                }
            }
        }, false)
    }
}
