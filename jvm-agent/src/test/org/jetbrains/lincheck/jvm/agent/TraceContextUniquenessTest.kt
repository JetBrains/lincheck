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

import junit.framework.TestCase.assertEquals
import org.jetbrains.lincheck.trace.TraceContext
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CollocationUniquenessTest {
    @Test
    fun testCodeLocationUniqueness() {
        val context = TraceContext()
        val ste = StackTraceElement("MyClass", "myMethod", "MyFile.kt", 10)
        
        val id1 = context.newCodeLocation(ste)
        val id2 = context.newCodeLocation(ste)
        
        assertEquals("IDs should be the same for identical code locations", id1, id2)
        assertEquals(1, context.codeLocations.size)
    }
    
    @Test
    fun testCodeLocationUniquenessDifferentObjSameContent() {
        val context = TraceContext()
        val ste = StackTraceElement("MyClass", "myMethod", "MyFile.kt", 10)
        val ste2 = StackTraceElement("MyClass", "myMethod", "MyFile.kt", 10)

        val id1 = context.newCodeLocation(ste)
        val id2 = context.newCodeLocation(ste2)

        assertEquals("IDs should be the same for identical code locations", id1, id2)
        assertEquals(1, context.codeLocations.size)
    }

    @Test
    fun testDifferentCodeLocations() {
        val context = TraceContext()
        val ste1 = StackTraceElement("MyClass", "myMethod", "MyFile.kt", 10)
        val ste2 = StackTraceElement("MyClass", "myMethod", "MyFile.kt", 11)
        
        val id1 = context.newCodeLocation(ste1)
        val id2 = context.newCodeLocation(ste2)
        
        assertNotEquals("IDs should be different for different code locations", id1, id2)
        assertEquals(2, context.codeLocations.size)
    }
}