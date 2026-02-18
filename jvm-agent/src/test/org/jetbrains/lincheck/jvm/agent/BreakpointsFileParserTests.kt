/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.*

class BreakpointsFileParserTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeBreakpointsFile(content: String): String {
        val file = tempFolder.newFile("breakpoints.ini")
        file.writeText(content)
        return file.absolutePath
    }

    // --- Empty file ---

    @Test
    fun testEmptyFile() {
        val path = writeBreakpointsFile("")
        val result = BreakpointsFileParser.parseBreakpointsFile(path)
        assertTrue(result.isEmpty())
    }

    // --- File does not exist ---

    @Test(expected = IllegalStateException::class)
    fun testFileDoesNotExist() {
        BreakpointsFileParser.parseBreakpointsFile("/non/existent/path/breakpoints.ini")
    }

    // --- One breakpoint, required fields only ---

    @Test
    fun testOneBreakpointRequiredFieldsOnly() {
        val path = writeBreakpointsFile("""
            [Breakpoint 1]
            className = org.example.MyClass
            fileName = MyClass.java
            lineNumber = 42
        """.trimIndent())

        val result = BreakpointsFileParser.parseBreakpointsFile(path)
        assertEquals(1, result.size)

        val bp = result[0]
        assertEquals("org.example.MyClass", bp.className)
        assertEquals("MyClass.java", bp.fileName)
        assertEquals(42, bp.lineNumber)
        assertNull(bp.conditionClassName)
        assertNull(bp.conditionFactoryMethodName)
        assertNull(bp.conditionCapturedVars)
        assertNull(bp.conditionCodeFragment)
    }

    // --- One breakpoint, all fields ---

    @Test
    fun testOneBreakpointAllFields() {
        val bytecode = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val encodedBytecode = Base64.getEncoder().encodeToString(bytecode)

        val path = writeBreakpointsFile("""
            [Breakpoint 1]
            className = org.example.MyClass
            fileName = MyClass.java
            lineNumber = 101
            conditionClassName = org.example.MyCondition
            conditionFactoryMethodName = create
            conditionCapturedVars = x,y,z
            conditionCodeFragment = $encodedBytecode
        """.trimIndent())

        val result = BreakpointsFileParser.parseBreakpointsFile(path)
        assertEquals(1, result.size)

        val bp = result[0]
        assertEquals("org.example.MyClass", bp.className)
        assertEquals("MyClass.java", bp.fileName)
        assertEquals(101, bp.lineNumber)
        assertEquals("org.example.MyCondition", bp.conditionClassName)
        assertEquals("create", bp.conditionFactoryMethodName)
        assertEquals(listOf("x", "y", "z"), bp.conditionCapturedVars)
        assertArrayEquals(bytecode, bp.conditionCodeFragment)
    }

    // --- Several breakpoints, different subsets of fields ---

    @Test
    fun testSeveralBreakpointsDifferentFields() {
        val bytecode = byteArrayOf(0x01, 0x02)
        val encodedBytecode = Base64.getEncoder().encodeToString(bytecode)

        val path = writeBreakpointsFile("""
            [Breakpoint 1]
            className = org.example.A
            fileName = A.java
            lineNumber = 10

            [Breakpoint 2]
            className = org.example.B
            fileName = B.java
            lineNumber = 20
            conditionClassName = org.example.Cond

            [Breakpoint 3]
            className = org.example.C
            fileName = C.java
            lineNumber = 30
            conditionClassName = org.example.Cond2
            conditionFactoryMethodName = make
            conditionCapturedVars = a, b
            conditionCodeFragment = $encodedBytecode
        """.trimIndent())

        val result = BreakpointsFileParser.parseBreakpointsFile(path)
        assertEquals(3, result.size)

        // first: required fields only
        val bp1 = result[0]
        assertEquals("org.example.A", bp1.className)
        assertEquals("A.java", bp1.fileName)
        assertEquals(10, bp1.lineNumber)
        assertNull(bp1.conditionClassName)
        assertNull(bp1.conditionFactoryMethodName)
        assertNull(bp1.conditionCapturedVars)
        assertNull(bp1.conditionCodeFragment)

        // second: required + conditionClassName only
        val bp2 = result[1]
        assertEquals("org.example.B", bp2.className)
        assertEquals("B.java", bp2.fileName)
        assertEquals(20, bp2.lineNumber)
        assertEquals("org.example.Cond", bp2.conditionClassName)
        assertNull(bp2.conditionFactoryMethodName)
        assertNull(bp2.conditionCapturedVars)
        assertNull(bp2.conditionCodeFragment)

        // third: all fields
        val bp3 = result[2]
        assertEquals("org.example.C", bp3.className)
        assertEquals("C.java", bp3.fileName)
        assertEquals(30, bp3.lineNumber)
        assertEquals("org.example.Cond2", bp3.conditionClassName)
        assertEquals("make", bp3.conditionFactoryMethodName)
        assertEquals(listOf("a", "b"), bp3.conditionCapturedVars)
        assertArrayEquals(bytecode, bp3.conditionCodeFragment)
    }

    // --- Section header is missing (property outside any section) ---

    @Test(expected = IllegalStateException::class)
    fun testPropertyOutsideSection() {
        val path = writeBreakpointsFile("""
            className = org.example.MyClass
            fileName = MyClass.java
            lineNumber = 42
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    // --- Non-standard section headers are forbidden ---

    @Test(expected = IllegalArgumentException::class)
    fun testArbitrarySectionNameRejected() {
        val path = writeBreakpointsFile("""
            [Foo]
            className = org.example.A
            fileName = A.java
            lineNumber = 1
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSectionNameWithoutBreakpointPrefix() {
        val path = writeBreakpointsFile("""
            [123]
            className = org.example.A
            fileName = A.java
            lineNumber = 1
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSectionNameBreakpointWithoutNumber() {
        val path = writeBreakpointsFile("""
            [Breakpoint]
            className = org.example.A
            fileName = A.java
            lineNumber = 1
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    // --- Invalid key-value syntax (no '=' sign) ---

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidKeyValueSyntax() {
        val path = writeBreakpointsFile("""
            [Breakpoint 1]
            className org.example.MyClass
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    // --- Comments only ---

    @Test
    fun testCommentsOnlyFile() {
        val path = writeBreakpointsFile("""
            # this is a hash comment
            ; this is a semicolon comment
            # another comment
        """.trimIndent())

        val result = BreakpointsFileParser.parseBreakpointsFile(path)
        assertTrue(result.isEmpty())
    }

    // --- Invalid comment syntax (not recognized as comments) ---

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidCommentSyntaxSlashSlash() {
        val path = writeBreakpointsFile("""
            // this is not a valid comment
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidCommentSyntaxInsideSection() {
        // "-- comment" has no '=' and is not '#' or ';', so fails as invalid line
        val path = writeBreakpointsFile("""
            [Breakpoint 1]
            -- not a comment
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    // --- Malformed INI ---

    @Test(expected = IllegalArgumentException::class)
    fun testArbitraryContentNotIni() {
        val path = writeBreakpointsFile("""
            This is just some random text
            that is definitely not INI format
            {"json": "also not ini"}
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testMalformedSectionHeader() {
        // "[Breakpoint 1" without closing bracket is not parsed as a section header,
        // falls through to key=value parsing, and has no '=' so fails
        val path = writeBreakpointsFile("""
            [Breakpoint 1
            className = org.example.MyClass
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    // --- Additional edge cases for required field validation ---

    @Test(expected = IllegalArgumentException::class)
    fun testMissingClassName() {
        val path = writeBreakpointsFile("""
            [Breakpoint 1]
            fileName = MyClass.java
            lineNumber = 42
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testMissingFileName() {
        val path = writeBreakpointsFile("""
            [Breakpoint 1]
            className = org.example.MyClass
            lineNumber = 42
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testMissingLineNumber() {
        val path = writeBreakpointsFile("""
            [Breakpoint 1]
            className = org.example.MyClass
            fileName = MyClass.java
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidLineNumber() {
        val path = writeBreakpointsFile("""
            [Breakpoint 1]
            className = org.example.MyClass
            fileName = MyClass.java
            lineNumber = abc
        """.trimIndent())

        BreakpointsFileParser.parseBreakpointsFile(path)
    }
}
