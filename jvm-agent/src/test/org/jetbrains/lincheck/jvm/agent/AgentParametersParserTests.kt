/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AgentParametersParserTests {
    @Before
    fun resetArgs() {
        TraceAgentParameters.reset()
    }

    @Test
    fun testOldStyleMinimal() {
        TraceAgentParameters.parseArgs("org.Class,method", emptyList())

        assertEquals("org.Class", TraceAgentParameters.classUnderTraceDebugging)
        assertEquals("org.Class", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_CLASS))

        assertEquals("method", TraceAgentParameters.methodUnderTraceDebugging)
        assertEquals("method", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_METHOD))

        assertNull(TraceAgentParameters.traceDumpFilePath)
        assertNull(TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_OUTPUT))
    }

    @Test
    fun testOldStyleWithOutput() {
        TraceAgentParameters.parseArgs("org.Class,method,/var/data", emptyList())

        assertEquals("org.Class", TraceAgentParameters.classUnderTraceDebugging)
        assertEquals("org.Class", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_CLASS))

        assertEquals("method", TraceAgentParameters.methodUnderTraceDebugging)
        assertEquals("method", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_METHOD))

        assertEquals("/var/data", TraceAgentParameters.traceDumpFilePath)
        assertEquals("/var/data", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_OUTPUT))
    }

    @Test
    fun testOldStyleWithOutputAndTwoOptions() {
        TraceAgentParameters.parseArgs("org.Class,method,/var/data,bin,stream", listOf("opt1", "opt2"))

        assertEquals("org.Class", TraceAgentParameters.classUnderTraceDebugging)
        assertEquals("org.Class", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_CLASS))

        assertEquals("method", TraceAgentParameters.methodUnderTraceDebugging)
        assertEquals("method", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_METHOD))

        assertEquals("/var/data", TraceAgentParameters.traceDumpFilePath)
        assertEquals("/var/data", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_OUTPUT))

        assertEquals("bin", TraceAgentParameters.getArg("opt1"))

        assertEquals("stream", TraceAgentParameters.getArg("opt2"))
    }

    @Test
    fun testOldStyleEscaping() {
        TraceAgentParameters.parseArgs("org.Class,method\\,comma\\\\backslash,c:\\\\temp\\\\data", emptyList())

        assertEquals("org.Class", TraceAgentParameters.classUnderTraceDebugging)
        assertEquals("org.Class", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_CLASS))

        assertEquals("method,comma\\backslash", TraceAgentParameters.methodUnderTraceDebugging)
        assertEquals("method,comma\\backslash", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_METHOD))

        assertEquals("c:\\temp\\data", TraceAgentParameters.traceDumpFilePath)
        assertEquals("c:\\temp\\data", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_OUTPUT))
    }

    @Test
    fun testNewStyleMinimal() {
        TraceAgentParameters.parseArgs("class=org.Class,method=method", emptyList())

        assertEquals("org.Class", TraceAgentParameters.classUnderTraceDebugging)
        assertEquals("org.Class", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_CLASS))

        assertEquals("method", TraceAgentParameters.methodUnderTraceDebugging)
        assertEquals("method", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_METHOD))

        assertNull(TraceAgentParameters.traceDumpFilePath)
        assertNull(TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_OUTPUT))
    }

    @Test
    fun testNewStyleMinimalOtherOrder() {
        TraceAgentParameters.parseArgs("method=method,class=org.Class", emptyList())

        assertEquals("org.Class", TraceAgentParameters.classUnderTraceDebugging)
        assertEquals("org.Class", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_CLASS))

        assertEquals("method", TraceAgentParameters.methodUnderTraceDebugging)
        assertEquals("method", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_METHOD))

        assertNull(TraceAgentParameters.traceDumpFilePath)
        assertNull(TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_OUTPUT))
    }

    @Test
    fun testNewStyleTrailingComma() {
        TraceAgentParameters.parseArgs("method=method,class=org.Class,", emptyList())

        assertEquals("org.Class", TraceAgentParameters.classUnderTraceDebugging)
        assertEquals("org.Class", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_CLASS))

        assertEquals("method", TraceAgentParameters.methodUnderTraceDebugging)
        assertEquals("method", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_METHOD))

        assertNull(TraceAgentParameters.traceDumpFilePath)
        assertNull(TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_OUTPUT))
    }

    @Test
    fun testNewStyleEscapesInValue() {
        TraceAgentParameters.parseArgs("method=method\\,comma\\\\backslash\\=equals,class=org.Class", emptyList())

        assertEquals("org.Class", TraceAgentParameters.classUnderTraceDebugging)
        assertEquals("org.Class", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_CLASS))

        assertEquals("method,comma\\backslash=equals", TraceAgentParameters.methodUnderTraceDebugging)
        assertEquals("method,comma\\backslash=equals", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_METHOD))

        assertNull(TraceAgentParameters.traceDumpFilePath)
        assertNull(TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_OUTPUT))
    }
    @Test

    fun testNewStyleQuotedValue() {
        TraceAgentParameters.parseArgs("method=\"method,comma\\backslash=equals\\\"quote\",class=org.Class", emptyList())

        assertEquals("org.Class", TraceAgentParameters.classUnderTraceDebugging)
        assertEquals("org.Class", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_CLASS))

        assertEquals("method,comma\\backslash=equals\"quote", TraceAgentParameters.methodUnderTraceDebugging)
        assertEquals("method,comma\\backslash=equals\"quote", TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_METHOD))

        assertNull(TraceAgentParameters.traceDumpFilePath)
        assertNull(TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_OUTPUT))
    }

    @Test(expected = IllegalStateException::class)
    fun testNewStyleBadKeyStart() {
        TraceAgentParameters.parseArgs("3key=bad", emptyList())
    }

    @Test(expected = IllegalStateException::class)
    fun testNewStyleBadKeyChar() {
        TraceAgentParameters.parseArgs("key$=bad", emptyList())

    }
    @Test(expected = IllegalStateException::class)
    fun testNewStyleNoValue() {
        TraceAgentParameters.parseArgs("key", emptyList())
    }

    @Test(expected = IllegalStateException::class)
    fun testNewStyleNeedCommaAfterQuote() {
        TraceAgentParameters.parseArgs("key1=\"xxx\"key2=v", emptyList())
    }

    @Test(expected = IllegalStateException::class)
    fun testNewStyleNeedClosingQuote() {
        TraceAgentParameters.parseArgs("key1=\"xxx", emptyList())
    }

    @Test(expected = IllegalStateException::class)
    fun testNewStyleNeedSomeEscape() {
        TraceAgentParameters.parseArgs("key1=xxx\\", emptyList())
    }
}