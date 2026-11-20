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

private class EventStructureObjectCloneTestBox(var value: Int) : Cloneable {
    public override fun clone(): EventStructureObjectCloneTestBox = super.clone() as EventStructureObjectCloneTestBox
}

/**
 * Checks that a clone made with `Object.clone()` appears in the execution graph as an allocation,
 * and that a write into one of the clone's own fields right after cloning is tracked.
 */
class EventStructureObjectCloneTest : BaseTraceRepresentationTest("event_structure_object_clone") {
    private val original = EventStructureObjectCloneTestBox(1)

    override fun operation() {
        val clone = original.clone()
        clone.value = 2
    }

    override fun ModelCheckingOptions.customize() {
        useExperimentalModelChecking()
        printExecutionGraph(true)
    }
}
