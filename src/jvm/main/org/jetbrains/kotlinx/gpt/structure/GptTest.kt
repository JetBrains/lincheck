/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.gpt.structure

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions

class GptTest {

    val counter = Counter()

    @Operation
    fun incrementAndGet() = counter.incrementAndGet()

    @Operation
    fun get() = counter.get()

}

fun modelCheckingFailure(): LincheckFailure? {
    return ModelCheckingOptions()
        .checkImpl(GptTest::class.java)
}