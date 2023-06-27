@file:Suppress("UNUSED")
/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.generator

import junit.framework.Assert.assertTrue
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck_test.verifier.linearizability.SpinLockBasedSet
import org.junit.Assert.*
import kotlin.math.pow

/**
 * This test checks that parameter generators random use different seeds than executions generator.
 * This test fails if seeds are equals.
 * [Corresponding bug description](https://github.com/Kotlin/kotlinx-lincheck/issues/120).
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
    fun test() = ModelCheckingOptions().check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type annotated with [Param] won't receive same values all the time
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1000")
class MethodParameterGenerationTestWithBothParametersAnnotated {
    @Operation
    fun operation(@Param(name = "key") first: Int, @Param(name = "key") second: Int) =
        throwInternalExceptionIfParamsNotEquals(first, second)

    @Test(expected = LincheckAssertionError::class)
    fun test() = ModelCheckingOptions().check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type, where the first one is annotated with [Param],
 *  won't receive same values all the time.
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1000")
class MethodParameterGenerationTestWithFirstParameterAnnotated {

    @Operation
    fun operation(@Param(name = "key") first: Int, second: Int) = throwInternalExceptionIfParamsNotEquals(first, second)

    @Test(expected = LincheckAssertionError::class)
    fun test() = ModelCheckingOptions().check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type, where the second one is annotated with [Param],
 *  won't receive same values all the time.
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1000")
class MethodParameterGenerationTestWithSecondParameterAnnotated {
    @Operation
    fun operation(first: Int, @Param(name = "key") second: Int) = throwInternalExceptionIfParamsNotEquals(first, second)

    @Test(expected = LincheckAssertionError::class)
    fun test() = ModelCheckingOptions().check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type won't receive same values all the time
 */
class MethodParameterGenerationTest {
    @Operation
    fun operation(first: Int, second: Int) = throwInternalExceptionIfParamsNotEquals(first, second)

    @Test(expected = LincheckAssertionError::class)
    fun test() = ModelCheckingOptions().check(this::class)

}

private fun throwInternalExceptionIfParamsNotEquals(first: Int, second: Int) {
    if (first != second) {
        throw InternalLincheckTestUnexpectedException
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

/**
 * This test ensures that parameter generator dynamically expanding range
 * will be shrunk to start size after each scenario run.
 *
 * [Corresponding issue description](https://github.com/Kotlin/kotlinx-lincheck/issues/179).
 */
class ParamGeneratorResetBetweenScenariosTest {

    @Test
    fun test() {
        ModelCheckingOptions()
            .threads(2)
            .actorsPerThread(5)
            .iterations(30)
            .check(this::class)
    }

    @Operation
    @Synchronized
    fun operation(value: Int) = check(value in -10..10)

}

class EnumParamGeneratorTest {

    private val generationBatchSize = 100

    @Test
    fun `should generate values increasing range and than re-shuffle enum values without configuration`() {
        val randomProvider = RandomProvider()
        val gen = EnumGen(TestEnum::class.java, randomProvider, "")

        val enumCountMapBeforeReset = generateCountMap(gen)
        assertDistribution(enumCountMapBeforeReset, TestEnum.values().toSet())

        gen.reset()

        val enumCountMapAfterReset = generateCountMap(gen)
        assertDistribution(enumCountMapAfterReset, TestEnum.values().toSet())

        assertNotEquals(enumCountMapBeforeReset, enumCountMapAfterReset)
    }

    @Test
    fun `should generate values increasing range and than re-shuffle enum values with configuration`() {
        val randomProvider = RandomProvider()
        val permittedValuesSet = hashSetOf(TestEnum.A, TestEnum.B, TestEnum.C, TestEnum.D)
        val gen = EnumGen(TestEnum::class.java, randomProvider, "A,B,C,D")

        val enumCountMapBeforeReset = generateCountMap(gen)
        assertDistribution(enumCountMapBeforeReset, permittedValuesSet)

        gen.reset()

        val enumCountMapAfterReset = generateCountMap(gen)
        assertDistribution(enumCountMapAfterReset, permittedValuesSet)

        assertNotEquals(enumCountMapBeforeReset, enumCountMapAfterReset)
    }

    private enum class TestEnum {
        A, B, C, D, E, F, G, H, X, Y
    }

    private fun generateCountMap(gen: EnumGen<TestEnum>): Map<TestEnum, Int> {
        return (0 until generationBatchSize).map { gen.generate() }.groupingBy { it }.eachCount()
    }

    private fun assertDistribution(enumCounts: Map<TestEnum, Int>, expectedValuesSet: Set<TestEnum>) {
        val dispersion = dispersion(enumCounts.entries.map { it.value })
        assertTrue(dispersion > 4.0)

        assertTrue(enumCounts.all { it.key in expectedValuesSet })
    }

    private fun dispersion(data: List<Int>): Double {
        val average = data.average()
        val squaredDifferences = data.map { x -> (x - average).pow(2.0) }

        return squaredDifferences.average()
    }

}

/**
 * This test checks enum generation with specified named enum generator
 */
@Param(name = "operation_type", gen = EnumGen::class)
@Param(name = "key", gen = IntGen::class, conf = "1:5")
class NamedEnumParamGeneratorTest {

    private val set = SpinLockBasedSet()

    @Operation
    fun operation(@Param(name = "operation_type") operation: OperationType, @Param(name = "key") key: Int): Boolean {
        return when (operation) {
            OperationType.ADD -> set.add(key)
            OperationType.REMOVE -> set.remove(key)
            OperationType.CONTAINS -> set.contains(key)
        }
    }

    @Test(expected = LincheckAssertionError::class)
    fun test() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .minimizeFailedScenario(false)
        .check(this::class)

    enum class OperationType {
        ADD,
        REMOVE,
        CONTAINS
    }
}

/**
 * This test checks enum generation with in-place configured unnamed enum generator
 */
@Param(name = "key", gen = IntGen::class, conf = "1:5")
class UnnamedEnumParamGeneratorTest() {
    private val set = SpinLockBasedSet()

    @Operation
    fun operation(@Param(gen = EnumGen::class) operation: OperationType, @Param(name = "key") key: Int): Boolean {
        return when (operation) {
            OperationType.ADD -> set.add(key)
            OperationType.REMOVE -> set.remove(key)
            OperationType.CONTAINS -> set.contains(key)
        }
    }

    @Test(expected = LincheckAssertionError::class)
    fun test() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .minimizeFailedScenario(false)
        .check(this::class)

    enum class OperationType {
        ADD,
        REMOVE,
        CONTAINS
    }
}

/**
 * Test checks that enum generator will be used even without [Param] annotation
 */
@Param(name = "key", gen = IntGen::class, conf = "1:5")
class EnumParamWithoutAnnotationGeneratorTest: BaseEnumSetTest() {

    @Operation
    fun operation(operation: OperationType, @Param(name = "key") key: Int): Boolean {
        return setOperation(operation, key)
    }

    @Test(expected = LincheckAssertionError::class)
    fun test() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .minimizeFailedScenario(false)
        .check(this::class)
}

abstract class BaseEnumSetTest {

    private val set = SpinLockBasedSet()
    fun setOperation(operation: OperationType,  key: Int): Boolean {
        return when (operation) {
            OperationType.ADD -> set.add(key)
            OperationType.REMOVE -> set.remove(key)
            OperationType.CONTAINS -> set.contains(key)
        }
    }
    enum class OperationType {
        ADD,
        REMOVE,
        CONTAINS
    }
}

/**
 * Test checks that if one named parameter generator is associated with many types, then exception will be thrown
 */
@Param(name = "type", gen = EnumGen::class)
class MultipleTypesAssociatedWithNamedEnumParameterGeneratorTest {

    @Operation
    fun operation(@Param(name = "type") first: FirstEnum, @Param(name = "type") secondEnum: SecondEnum) = Unit

    @Test
    fun test() {
        val exception = assertThrows(IllegalStateException::class.java) { ModelCheckingOptions().check(this::class) }
        assertEquals(
            "Enum param gen with name type can't be associated with two different types: FirstEnum and SecondEnum",
            exception.message
        )
    }

    enum class FirstEnum { A, B }

    enum class SecondEnum { A, B }

}

/**
 * Checks configuration works with enums with spaces in values names
 */
@Param(name = "type", gen = EnumGen::class, conf = "FIRST OPTION, SECOND OPTION")
class EnumsWithWhitespacesInNameConfigurationTest {

    @Operation
    fun operation(@Param(name = "type") param: WeirdEnum) = 0
    @Test
    fun test() = ModelCheckingOptions().check(this::class)

    enum class WeirdEnum {
        `FIRST OPTION`,
        `SECOND OPTION`,
        `OTHER OPTION`
    }
}

