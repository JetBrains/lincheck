/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.util.InternalLincheckExceptionEmulator.throwException
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.*

/**
 * This test checks that if exception is thrown from the Lincheck itself, it will be reported properly.
 * Bug exception is emulated using [org.jetbrains.kotlinx.lincheck.util.InternalLincheckExceptionEmulator],
 * which is located in org.jetbrains.kotlinx.lincheck package, so exception will be treated like internal bug.
 */
@Suppress("UNUSED")
class InternalLincheckBugTest {

    private var canEnterForbiddenSection = false

    @Operation
    fun operation1() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
    }

    @Operation
    fun operation2() {
        if (canEnterForbiddenSection) throwException()
    }

    @Test
    fun test() = ModelCheckingOptions()
        .actorsPerThread(2)
        .checkImpl(this::class.java)
        .checkLincheckOutput("internal_bug_report.txt")
}
