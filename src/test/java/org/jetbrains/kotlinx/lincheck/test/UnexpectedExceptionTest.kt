package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*

class UnexpectedExceptionTest : AbstractLincheckTest(UnexpectedExceptionFailure::class.java.kotlin) {
    private var canEnterForbiddenSection = false

    @Operation
    fun operation1() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
    }

    @Operation(handleExceptionsAsResult = [IllegalArgumentException::class])
    fun operation2() {
        check(!canEnterForbiddenSection)
    }

    override fun extractState(): Any = canEnterForbiddenSection
}
