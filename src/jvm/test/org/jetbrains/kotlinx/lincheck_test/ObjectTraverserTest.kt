/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.enumerateObjects
import org.junit.Assert
import org.junit.Test

/**
 * Checks invariants and restrictions on [enumerateObjects] method.
 */
class ObjectTraverserTest {

    @Test
    fun `should not traverse class and classLoader recursively while enumerating objects`() {
        val myObject = @Suppress("unused") object : Any() {
            var clazz: Class<*>? = this::class.java
            var classLoader: ClassLoader? = this::class.java.classLoader
            var integer: Int = 10
        }
        val objectEnumeration = enumerateObjects(myObject)

        Assert.assertTrue(objectEnumeration.keys.none { it is Class<*> || it is ClassLoader })
    }

}