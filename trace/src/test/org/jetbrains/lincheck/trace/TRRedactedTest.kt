/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID

class TRRedactedTest {
    private val context = TraceContext()

    @Test
    fun `representation contains only type and safe policy attribution`() {
        val marker = TRRedacted(
            classDescriptor = context.createAndRegisterClassDescriptor("java.lang.String"),
            templateUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
            templateName = "GDPR defaults",
        )

        assertEquals("[redacted: String] (GDPR defaults)", marker.toString())
        assertFalse(marker.toString().contains("550e8400"))
    }

    @Test
    fun `unattributed marker remains typed`() {
        val integer = context.createAndRegisterClassDescriptor("java.lang.Integer")
        assertEquals("[redacted: Integer]", TRRedacted(integer, null, null).toString())
        assertEquals("[redacted: unknown]", TRRedacted(null, null, null).toString())
    }
}
