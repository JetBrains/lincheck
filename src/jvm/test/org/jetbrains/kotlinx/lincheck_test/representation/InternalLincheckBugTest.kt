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

import org.jetbrains.kotlinx.lincheck.util.InternalLincheckExceptionEmulator
import org.jetbrains.kotlinx.lincheck_test.util.isJdk8

/**
 * This test checks that if exception is thrown from the Lincheck itself, it will be reported properly.
 * Bug exception is emulated using [org.jetbrains.kotlinx.lincheck.util.InternalLincheckExceptionEmulator],
 * which is located in org.jetbrains.kotlinx.lincheck package, so exception will be treated like internal bug.
 */
@Suppress("UNUSED")
class InternalLincheckBugTest : BaseTraceRepresentationTest(
    if (isJdk8) "internal_bug_report_jdk8.txt" else "internal_bug_report.txt"
) {

    override fun operation() {
        InternalLincheckExceptionEmulator.throwException()
    }

}
