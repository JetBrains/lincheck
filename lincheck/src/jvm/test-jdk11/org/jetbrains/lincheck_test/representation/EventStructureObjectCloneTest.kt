/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.representation

import org.jetbrains.lincheck.LincheckSettings

private class EventStructureObjectCloneTestBox(var value: Int) : Cloneable {
    public override fun clone(): EventStructureObjectCloneTestBox = super.clone() as EventStructureObjectCloneTestBox
}

/**
 * Checks how `Object.clone()` and a subsequent write into the clone appear in the trace.
 */
class EventStructureObjectCloneTest : BaseTraceRepresentationTest("event_structure_object_clone") {
    private val original = EventStructureObjectCloneTestBox(1)

    override fun operation() {
        val clone = original.clone()
        clone.value = 2
    }

    override fun LincheckSettings.customize() {
        modelChecking { enablePartialOrderReduction() }
    }
}
