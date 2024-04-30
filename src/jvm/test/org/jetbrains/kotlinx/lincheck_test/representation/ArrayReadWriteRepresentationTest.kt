/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test

/**
 * Check an array read and write operation representation.
 */
class ArrayReadWriteRepresentationTest {

    private val flags = IntArray(2)
    @Volatile
    private var counter = 0

    @Operation
    fun increment(): Int {
        val value = flags[0]
        flags[0] = value + 1
        val result = counter++
        flags[1] = 0
        return result
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::increment) }
                thread { actor(::increment) }
            }
        }
        .checkImpl(this::class.java)
        .checkLincheckOutput("array_read_write.txt")

}