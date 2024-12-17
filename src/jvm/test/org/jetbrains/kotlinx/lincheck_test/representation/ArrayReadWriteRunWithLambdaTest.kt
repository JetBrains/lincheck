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

import kotlin.random.Random

class ArrayReadWriteRunWithLambdaTest : BaseRunWithLambdaRepresentationTest("array_read_write_run_with_lambda.txt") {
    private val array = IntArray(3)

    @Suppress("UNUSED_VARIABLE")
    override fun operation() {
        val index = Random.nextInt(array.size)
        array[index]++
        val y = array[index]
    }
}