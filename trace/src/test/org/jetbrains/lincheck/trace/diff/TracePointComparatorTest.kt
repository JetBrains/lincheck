/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.diff

import org.jetbrains.lincheck.trace.TRObject
import org.jetbrains.lincheck.trace.TRObjectSnapshot
import org.jetbrains.lincheck.trace.TRSnapshotLineBreakpointTracePoint
import org.jetbrains.lincheck.trace.TRValue
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.UNKNOWN_CODE_LOCATION_ID
import org.jetbrains.lincheck.trace.createAndRegisterClassDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Covers the `rendered` discriminator on [TracePointComparator]'s
 * `TRObject` / `TRObjectSnapshot` branches — JBRes-9536 addendum §B.
 */
class TracePointComparatorTest {

    private val ctx = TraceContext()
    private val ownerCd = ctx.createAndRegisterClassDescriptor("org.example.Owner")
    private val bpUuid: UUID = UUID(0x1234L, 0x5678L)

    @Test
    fun `two TRObjectSnapshots with same identity but different rendered are not strict-equal`() {
        val left = wrap(TRObjectSnapshot(ownerCd, identity = 0x16309L, rendered = "v=1", fields = emptyMap()))
        val right = wrap(TRObjectSnapshot(ownerCd, identity = 0x16309L, rendered = "v=2", fields = emptyMap()))
        assertFalse(TracePointComparator.strictEqual(left, right))
    }

    @Test
    fun `two TRObjectSnapshots with same identity and same rendered are strict-equal`() {
        val left = wrap(TRObjectSnapshot(ownerCd, identity = 0x16309L, rendered = "v=1", fields = emptyMap()))
        val right = wrap(TRObjectSnapshot(ownerCd, identity = 0x16309L, rendered = "v=1", fields = emptyMap()))
        assertTrue(TracePointComparator.strictEqual(left, right))
    }

    @Test
    fun `two TRObjects with same identity but different rendered are not strict-equal`() {
        val left = wrap(TRObject(ownerCd, identity = 0x16309L, rendered = "v=1"))
        val right = wrap(TRObject(ownerCd, identity = 0x16309L, rendered = "v=2"))
        assertFalse(TracePointComparator.strictEqual(left, right))
    }

    @Test
    fun `null and non-null rendered are distinguished`() {
        val nullSide = wrap(TRObjectSnapshot(ownerCd, identity = 0x16309L, rendered = null, fields = emptyMap()))
        val present = wrap(TRObjectSnapshot(ownerCd, identity = 0x16309L, rendered = "v=1", fields = emptyMap()))
        assertFalse(TracePointComparator.strictEqual(nullSide, present))
    }

    @Test
    fun `two TRObjectSnapshots that differ only in rendered remain edit-independent-equal`() {
        // editIndependentHash skips per-tracepoint locals payload — so a rendered
        // change alone must NOT desync the trace-diff structural pass.
        val left = wrap(TRObjectSnapshot(ownerCd, identity = 0x16309L, rendered = "v=1", fields = emptyMap()))
        val right = wrap(TRObjectSnapshot(ownerCd, identity = 0x16309L, rendered = "v=2", fields = emptyMap()))
        assertTrue(TracePointComparator.editIndependentEqual(left, right))
    }

    private fun wrap(value: TRValue): TRSnapshotLineBreakpointTracePoint = TRSnapshotLineBreakpointTracePoint(
        context = ctx,
        codeLocationId = UNKNOWN_CODE_LOCATION_ID,
        threadId = 0,
        breakpointUuid = bpUuid,
        stackTraceCodeLocationIds = emptyList(),
        currentTimeMillis = 0L,
        locals = listOf(value),
        traceId = null,
        eventId = 0,
    )
}
