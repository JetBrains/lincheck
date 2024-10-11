/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.junit.Ignore
import org.junit.Test

/**
 * This test checks that despite some object isn't explicitly assigned to some shared value,
 * is won't be treated like a local object if we wrote it into some shared object using [VarHandle].
 *
 * If we hadn't such check, this test would hang due to infinite spin-loop on a local object operations with
 * no chances to detect a cycle and switch.
 */
class VarHandleTest {
    @Test
    @Ignore("VarHandles are not available in JDK 8")
    fun varHandleTest() {
        error("The test must fail!")
    }
}