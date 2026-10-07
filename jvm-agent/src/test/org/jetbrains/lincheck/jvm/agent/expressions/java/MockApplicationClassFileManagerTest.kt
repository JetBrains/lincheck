/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.java

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation.CLASS_PATH
import javax.tools.ToolProvider

class MockApplicationClassFileManagerTest {
    @Test
    fun packageExistenceProbeDoesNotResolveEveryClass() {
        var resolutions = 0
        val classes = (1..100).map { index ->
            object : SimpleJavaFileObject(URI.create("mem:///sample/Class$index.class"), JavaFileObject.Kind.CLASS) {}
        }
        val delegate = object : ForwardingJavaFileManager<JavaFileManager>(
            ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null),
        ) {
            override fun list(
                location: JavaFileManager.Location,
                packageName: String,
                kinds: MutableSet<JavaFileObject.Kind>,
                recurse: Boolean,
            ): Iterable<JavaFileObject> = classes

            override fun inferBinaryName(location: JavaFileManager.Location, file: JavaFileObject): String {
                resolutions++
                return "sample.${file.name.substringAfterLast('/').removeSuffix(".class")}"
            }
        }
        MockApplicationClassFileManager(delegate).use { manager ->
            val listed = manager.list(CLASS_PATH, "sample", mutableSetOf(JavaFileObject.Kind.CLASS), true)
            // ECJ checks package existence with hasNext; binary-name lookup scans its classpath again.
            val iterator = listed.iterator()
            assertTrue(iterator.hasNext())
            assertEquals("A package probe must not resolve class names", 0, resolutions)
            val first = iterator.next()
            assertEquals(1, resolutions)
            assertEquals("sample.Class1", manager.inferBinaryName(CLASS_PATH, first))
            assertEquals("A shadow must retain its already resolved binary name", 1, resolutions)
            assertEquals("Listing must be reusable", 100, listed.count())
        }
    }
}
