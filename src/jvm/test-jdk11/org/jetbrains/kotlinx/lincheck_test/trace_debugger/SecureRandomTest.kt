/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.trace_debugger

import java.security.SecureRandom

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.util.JdkVersion
import org.jetbrains.kotlinx.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.util.jdkVersion
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * A test class for validating the determinism of the [SecureRandom`-specific methods.
 */
class SecureRandomTest : RandomTests() {
    @Before
    fun setUp() {
        assumeTrue(isInTraceDebuggerMode)
        // https://github.com/JetBrains/lincheck/issues/564
        assumeFalse(jdkVersion == JdkVersion.JDK_21 || jdkVersion == JdkVersion.JDK_20)
    }
    
    @Operation
    fun operation(): String {
        val secureRandom = SecureRandom()
        val nextInt = secureRandom.nextInt()
        val seed = secureRandom.generateSeed(3)
        val seed1 = SecureRandom.getSeed(3)
        val bytes2 = ByteArray(3)
        val output = runCatching {
            secureRandom.nextBytes(bytes2, SecureRandom.getInstance("DRBG").parameters) 
        }.toString()
        secureRandom.setSeed(seed)
        
        return "$nextInt ${seed.asList()} ${seed1.asList()} ${secureRandom.algorithm}\n$output"
    }
}
