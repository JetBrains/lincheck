/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.InternalLincheckTestUnexpectedException
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest

class ExpectedTransformedExceptionTest : AbstractLincheckTest() {
    @Operation
    fun operation(): Unit = throw CustomException()

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
    }
}

class UnexpectedTransformedExceptionTest : AbstractLincheckTest(UnexpectedExceptionFailure::class) {
    @Volatile
    var throwException = false

    @Operation
    fun operation(): Int {
        throwException = true
        throwException = false
        if (throwException)
            throw InternalLincheckTestUnexpectedException
        return 0
    }
}

internal class CustomException : Throwable()
