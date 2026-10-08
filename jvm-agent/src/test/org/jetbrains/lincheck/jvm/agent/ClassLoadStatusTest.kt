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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLClassLoader

/** Tests for [LincheckInstrumentation.isClassLoaded] / [isClassAlreadyLoaded]. */
class ClassLoadStatusTest {

    /** `isClassLoaded` reads `instrumentation.allLoadedClasses`, so it needs an attached agent. */
    @Before
    fun attachAgent() {
        if (!LincheckInstrumentation.isInitialized) {
            LincheckInstrumentation.attachJavaAgentDynamically()
        }
    }

    @Test
    fun `bootstrap-loaded classes count as loaded from any class loader`() {
        // `String.class.getClassLoader()` is `null`, which no parent chain contains,
        // and an isolated loader is the production shape of the tracing agent's payload loader.
        val isolatedLoader = URLClassLoader(emptyArray(), ClassLoader.getSystemClassLoader().parent)
        assertTrue(isClassAlreadyLoaded("java/lang/String", isolatedLoader))
        assertTrue(isClassAlreadyLoaded("java/lang/String", ClassLoader.getSystemClassLoader()))
    }

    @Test
    fun `classes of this test are loaded, absent ones are not`() {
        val loader = javaClass.classLoader
        assertTrue(isClassAlreadyLoaded(javaClass.name.toInternalClassName(), loader))
        assertFalse(isClassAlreadyLoaded("org/jetbrains/lincheck/jvm/agent/NoSuchClass", loader))
    }
}
