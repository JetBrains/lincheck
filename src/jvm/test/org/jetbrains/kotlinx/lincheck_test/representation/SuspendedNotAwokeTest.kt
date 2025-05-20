/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import kotlinx.coroutines.channels.Channel
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.Test

/**
 * Covers a scenario when operation is suspended but never awoke.
 */
// TODO investigate difference for trace debugger (Evgeniy Moiseenko)
class SuspendedNotAwokeTest {

    @Volatile
    private var allowed: Boolean = false
    private val neverWakeUpChannel = Channel<Unit>()

    @Operation
    fun trigger() {
        allowed = true
        allowed = false
    }

    @Operation
    suspend fun operation() {
        if (allowed) {
            neverWakeUpChannel.receive()
        }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::trigger) }
                thread { actor(::operation) }
            }
        }
        .checkImpl(this::class.java)
        .checkLincheckOutput("suspended_not_awoke")

}