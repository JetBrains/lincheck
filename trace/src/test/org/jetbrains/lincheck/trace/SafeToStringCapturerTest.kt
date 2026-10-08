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

import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

class SafeToStringCapturerTest {

    init {
        if (!LincheckInstrumentation.isInitialized) {
            LincheckInstrumentation.attachJavaAgentDynamically()
        }
    }

    private val capturer: (Any) -> String?
        get() = SafeToStringCapturer::captureToString

    // ── Required by JBRes-9536 ──────────────────────────────────────────────
    //
    // UUID and java.time.* value types are on the class-level whitelist in
    // SafeToStringCapturer (UUID is final, every java.time class below is
    // final and has a side-effect-free toString). The whitelist bypasses
    // the bytecode analyzer so all JDKs in the build matrix (8/11/17/21)
    // produce the same captured text without per-JDK gates.

    @Test
    fun `UUID toString is captured safely`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
        assertEquals("00000000-0000-0000-0000-000000000001", capturer(uuid))
    }

    @Test
    fun `LocalDate toString is captured safely`() {
        val date = LocalDate.of(2026, 5, 25)
        assertEquals("2026-05-25", capturer(date))
    }

    @Test
    fun `LocalTime toString is captured safely`() {
        val t = LocalTime.of(14, 30, 0)
        assertEquals("14:30", capturer(t))
    }

    @Test
    fun `LocalDateTime toString is captured safely`() {
        // Whitelisted at the class level; the bytecode-only analyzer rejects this
        // toString because it delegates to java/time/* stdlib INVOKEVIRTUALs
        // the visitor flags top-down.
        val dt = LocalDateTime.of(2026, 5, 25, 14, 30, 0)
        assertEquals(dt.toString(), capturer(dt))
    }

    @Test
    fun `Instant toString is captured safely`() {
        // Whitelisted at the class level; the bytecode-only analyzer rejects this
        // toString because DateTimeFormatter.ISO_INSTANT.format exceeds the
        // analyzer's max recursion depth (5).
        val instant = Instant.ofEpochSecond(1716639000)
        assertEquals(instant.toString(), capturer(instant))
    }

    @Test
    fun `Duration toString is captured safely`() {
        // Whitelisted at the class level; the bytecode-only analyzer rejects this
        // toString because Duration.toString has a loop trimming trailing zeros
        // from the seconds string and SafetyViolation.LoopDetected is unsafe.
        val duration = Duration.ofSeconds(60)
        assertEquals(duration.toString(), capturer(duration))
    }

    // ── Negative cases — captured == null is the contract here ──────────────
    //
    // Non-whitelisted, non-final stdlib toString shapes that the bytecode
    // analyzer rejects. They prove the analyzer fallback still applies for
    // classes outside the WHITELISTED_TO_STRING_CLASSES set.

    @Test
    fun `BigInteger toString returns null (non-final class - MethodNotFinal)`() {
        // BigInteger is not final → SafetyViolation.MethodNotFinal.
        assertNull(capturer(BigInteger.valueOf(42)))
    }

    @Test
    fun `BigDecimal toString returns null (non-final class - MethodNotFinal)`() {
        // BigDecimal is not final → SafetyViolation.MethodNotFinal.
        assertNull(capturer(BigDecimal.valueOf(1L)))
    }

    @Test
    fun `File toString returns null (non-final class - MethodNotFinal)`() {
        // java.io.File is not final → SafetyViolation.MethodNotFinal.
        assertNull(capturer(File("/tmp/example.txt")))
    }

    // ── Hermetic synthetic baselines ────────────────────────────────────────
    //
    // Validate the bytecode-analysis path is still wired up for non-whitelisted
    // classes: SafeReturning is accepted, Unsafe is rejected.

    @Test
    fun `capturer returns toString for a side-effect-free toString`() {
        assertEquals("safe-constant", capturer(SafeReturning))
    }

    @Test
    fun `capturer returns null for a side-effecting toString`() {
        // Local class whose toString writes a static field — a textbook side effect.
        assertNull(capturer(Unsafe()))
    }

    private object SafeReturning {
        override fun toString(): String = "safe-constant"
    }

    @Suppress("unused")
    private class Unsafe {
        override fun toString(): String {
            counter++
            return "x"
        }
        companion object {
            @JvmStatic var counter: Int = 0
        }
    }
}
