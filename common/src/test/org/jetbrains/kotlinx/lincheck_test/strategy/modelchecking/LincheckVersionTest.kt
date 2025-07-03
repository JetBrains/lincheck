/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking

import org.jetbrains.kotlinx.lincheck.*
import org.junit.Assert
import org.junit.Test

class LincheckVersionTest {
    @Test
    fun `test version should be accessible at runtime`() {
        val version = lincheckVersion
        Assert.assertNotNull(version)
        Assert.assertTrue(version.matches("\\d+(\\.\\d+)+(-SNAPSHOT)?".toRegex()))
    }
}
