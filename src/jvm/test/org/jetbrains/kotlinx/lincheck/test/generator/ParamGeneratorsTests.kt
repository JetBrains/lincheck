/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.test.generator

import junit.framework.Assert.assertTrue
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.RandomProvider
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

/**
 * This test checks that parameter generators random use different seeds than executions generator.
 * This test fails if seeds are equals.
 * [Corresponding bug description](http://example.com).
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1")
class GeneratorSeedTest {
    private val array = IntArray(size = 2)

    @Operation
    fun get(@Param(name = "key") key: Int) = array[key]

    @Operation
    fun inc(@Param(name = "key") key: Int) {
        array[key] += 1
    }

    @Test(expected = LincheckAssertionError::class)
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type annotated with [Param] won't receive same values all the time
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1000")
class MethodParameterGenerationTestWithBothParametersAnnotated {
    @Operation
    fun operation(@Param(name = "key") first: Int, @Param(name = "key") second: Int) =
        checkParameters(first, second)

    @Test(expected = IllegalStateException::class)
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type, where the first one is annotated with [Param],
 *  won't receive same values all the time.
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1000")
class MethodParameterGenerationTestWithFirstParameterAnnotated {

    @Operation
    fun operation(@Param(name = "key") first: Int, second: Int) = checkParameters(first, second)

    @Test(expected = IllegalStateException::class)
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type, where the second one is annotated with [Param],
 *  won't receive same values all the time.
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1000")
class MethodParameterGenerationTestWithSecondParameterAnnotated {
    @Operation
    fun operation(first: Int, @Param(name = "key") second: Int) = checkParameters(first, second)

    @Test(expected = IllegalStateException::class)
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type won't receive same values all the time
 */
class MethodParameterGenerationTest {
    @Operation
    fun operation(first: Int, second: Int) = checkParameters(first, second)

    @Test(expected = IllegalStateException::class)
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)

}

private object DifferentParametersAsExpectedException : Exception()

private fun checkParameters(first: Int, second: Int) {
    if (first != second) {
        throw DifferentParametersAsExpectedException
    }
}

class StringParamGeneratorTest {

    @Test
    fun `should eventually increase generated word length`() {
        val alphabet = "abcdef"
        val maxStringLength = 10

        val randomProvider = RandomProvider()
        val alphabetSet = alphabet.toHashSet()
        val gen = StringGen(randomProvider, "$maxStringLength:$alphabet")

        val generatedStrings = (0 until 500).map {
            gen.generate()
        }

        // check all symbols are in alphabet
        generatedStrings.forEach { word -> assertTrue(word.all { it in alphabetSet }) }

        // check that the size does not decrease
        val generatesStringsLengths = generatedStrings.map { it.length }
        generatesStringsLengths.windowed(2).forEach { (prevLength, nextLength) ->
            assertTrue(prevLength <= nextLength)
        }

        // check size eventually increases
        assertTrue(generatesStringsLengths.groupBy { it }.size > 1)
    }

}