/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.Arrays

/**
 * Checks how array writes and [Arrays.fill] calls appear in an event-structure trace.
 */
class EventStructureArraysFillTest : BaseTraceRepresentationTest("event_structure_arrays_fill") {
    private val array = IntArray(2)

    override fun operation() {
        array[0] = 1
        Arrays.fill(array, 2)
    }

    override fun ModelCheckingOptions.customize() {
        useExperimentalModelChecking()
    }
}
