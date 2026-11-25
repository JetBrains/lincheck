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

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions

/**
 * Checks how a `java.lang.reflect.Field.set` write appears in an event-structure trace.
 */
class EventStructureReflectiveFieldWriteTest : BaseTraceRepresentationTest("event_structure_reflective_field_write") {
    private var value = 1

    override fun operation() {
        val field = EventStructureReflectiveFieldWriteTest::class.java
            .getDeclaredField("value")
            .apply { isAccessible = true }
        field.set(this, 2)
        check(field.getInt(this) == 2)
        check(field.getLong(this) == 2L)
        check(value == 2)
    }

    override fun ModelCheckingOptions.customize() {
        useExperimentalModelChecking()
    }
}
