/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.datastructures

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.ExpandingRangeIntGenerator.NextExpansionDirection.*
import java.util.*

/**
 * The implementation of this interface is used to generate parameters
 * for [operation][org.jetbrains.kotlinx.lincheck.annotations.Operation].
 *
 * Now all successors must have constructor with [RandomProvider] and config [String].
 */
interface ParameterGenerator<T> {
    fun generate(): T

    /**
     * Resets current range bounds to start, if this generator has such expanding range.
     * Meanwhile, it shouldn't reset random to avoid undesired correlation between scenarios.
     */
    fun reset() {}
}

/**
 * Used only as a default value in [org.jetbrains.kotlinx.lincheck.annotations.Operation] annotation, as it's impossible to use `null` as a default value in Java
 */
internal object DummyParameterGenerator : ParameterGenerator<Any?> {
    override fun generate() = throw UnsupportedOperationException()
}

/**
 * @param configuration configuration in format EnumType.ConstantA,EnumType.ConstantB,..,EnumType.ConstantN
 */
class EnumGen<T : Enum<T>>(enumClass: Class<out T>, randomProvider: RandomProvider, configuration: String) :
    ParameterGenerator<T> {

    private val enumValues: MutableList<T>
    private val intGenerator: ExpandingRangeIntGenerator
    private val shuffleRandom = randomProvider.createRandom()

    init {
        val allEnumValues = enumClass.enumConstants.toList()

        enumValues = if (configuration.isEmpty()) allEnumValues else {
            configuration.replace("(\\s*),(\\s*)".toRegex(), ",").split(",").map { enumStr ->
                allEnumValues.find { it.name == enumStr }
                    ?: throw IllegalArgumentException(
                        """
                           Bad configuration for enum ${enumClass.name}. No enum constant with name $enumStr
                           Configuration must be permitted enum values split by coma, or empty
                        """.trimIndent()
                    )
            }
        }.toMutableList()

        require(enumValues.isNotEmpty()) { "Bad configuration for enum ${enumClass.name}. Permitted enum values can't be empty" }

        intGenerator = ExpandingRangeIntGenerator(randomProvider.createRandom(), 0, enumValues.lastIndex)
    }

    override fun generate(): T = enumValues[intGenerator.nextInt()]

    override fun reset() {
        intGenerator.resetRange()
        enumValues.shuffle(shuffleRandom)
    }
}

class IntGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Int> {
    private val generator: ExpandingRangeIntGenerator

    init {
        generator = ExpandingRangeIntGenerator(
            random = randomProvider.createRandom(),
            configuration = configuration,
            minValueInclusive = Int.MIN_VALUE,
            maxValueInclusive = Int.MAX_VALUE,
            type = "int"
        )
    }

    override fun generate(): Int = generator.nextInt()
    override fun reset() = generator.resetRange()
}

class BooleanGen(
    randomProvider: RandomProvider,
    @Suppress("UNUSED_PARAMETER")
    configuration: String
) : ParameterGenerator<Boolean> {
    private val random = randomProvider.createRandom()

    override fun generate() = random.nextBoolean()
}

/**
 * @param configuration configuration in format minValueInclusive:maxValueInclusive, values must be in `Byte` type bounds
 */
class ByteGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Byte> {
    private val generator: ExpandingRangeIntGenerator

    init {
        generator = ExpandingRangeIntGenerator(
            random = randomProvider.createRandom(),
            configuration = configuration,
            minValueInclusive = Byte.MIN_VALUE.toInt(),
            maxValueInclusive = Byte.MAX_VALUE.toInt(),
            type = "byte"
        )
    }

    override fun generate(): Byte = generator.nextInt().toByte()

    override fun reset() = generator.resetRange()
}

class DoubleGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Double> {
    private val intGenerator: ExpandingRangeIntGenerator
    private val step: Double
    private val begin: Double

    init {
        val begin: Double
        val end: Double
        val step: Double

        if (configuration.isEmpty()) { // use default configuration
            begin = Int.MIN_VALUE.toDouble()
            end = Int.MAX_VALUE.toDouble()
            step = DEFAULT_STEP.toDouble()
        } else {
            val args = configuration.replace("\\s", "").split(":")

            when (args.size) {
                2 -> {
                    begin = args[0].toDouble()
                    end = args[1].toDouble()
                    step = (end - begin) / 100 // default generated step
                }

                3 -> {
                    begin = args[0].toDouble()
                    step = args[1].toDouble()
                    end = args[2].toDouble()
                }

                else -> throw IllegalArgumentException("Configuration should have two (begin and end) " + "or three (begin, step and end) arguments separated by colon")
            }
        }

        require(begin < end) { "Illegal range for type double: begin must be < end" }

        val delta = end - begin
        val maxSteps = (delta / step).toInt()

        intGenerator = ExpandingRangeIntGenerator(
            random = randomProvider.createRandom(),
            minValueInclusive = 0,
            maxValueInclusive = maxSteps
        )

        this.step = step
        this.begin = begin
    }

    override fun generate(): Double = begin + step * intGenerator.nextInt()

    override fun reset() = intGenerator.resetRange()

    companion object {
        private const val DEFAULT_STEP = 0.1f
    }
}

class FloatGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Float> {
    private val doubleGen = DoubleGen(randomProvider, configuration)

    override fun generate(): Float = doubleGen.generate().toFloat()
    override fun reset() = doubleGen.reset()
}


class LongGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Long> {
    private val intGen: IntGen = IntGen(randomProvider, configuration)

    override fun generate(): Long = intGen.generate().toLong()
    override fun reset() = intGen.reset()
}

/**
 * @param configuration configuration in format minValueInclusive:maxValueInclusive, values must be in `Short` type bounds
 */
class ShortGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Short> {
    private val generator: ExpandingRangeIntGenerator = ExpandingRangeIntGenerator(
        random = randomProvider.createRandom(),
        configuration = configuration,
        minValueInclusive = Byte.MIN_VALUE.toInt(),
        maxValueInclusive = Byte.MAX_VALUE.toInt(),
        type = "byte"
    )

    override fun generate(): Short = generator.nextInt().toShort()
    override fun reset() = generator.resetRange()
}

class StringGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<String> {
    private val random: Random
    private var maxWordLength: Int
    private var alphabet: CharArray
    private var currentWordLength = 1

    init {
        random = randomProvider.createRandom()

        if (configuration.isEmpty()) { // use default configuration
            maxWordLength = DEFAULT_MAX_WORD_LENGTH
            alphabet = DEFAULT_ALPHABET
        } else {
            val firstCommaIndex = configuration.indexOf(':')
            if (firstCommaIndex < 0) { // maxWordLength only
                maxWordLength = configuration.toInt()
                alphabet = DEFAULT_ALPHABET
            } else { // maxWordLength:alphabet
                maxWordLength = configuration.substring(0, firstCommaIndex).toInt()
                alphabet = configuration.substring(firstCommaIndex + 1).toCharArray()
            }
        }
    }

    override fun generate(): String {
        if (currentWordLength < maxWordLength && random.nextDouble() > 0.5) {
            currentWordLength++
        }
        val cs = CharArray(currentWordLength)
        for (i in cs.indices) {
            cs[i] = alphabet[random.nextInt(alphabet.size)]
        }
        return String(cs)
    }

    override fun reset() {
        currentWordLength = 1
    }

    companion object {
        // We keep this bound to avoid too big strings generation
        private const val DEFAULT_MAX_WORD_LENGTH = 15
        private val DEFAULT_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_ ".toCharArray()
    }
}

/**
 * This generator puts the number of the executing thread as the parameter value.
 * The `0`-th thread specifies the init part of the execution, while the `t+1`-th thread references the post part
 * (here we assume that the parallel part has `t` threads).
 *
 * Note, that this API is unstable and is subject to change.
 */
@Suppress("UNUSED_PARAMETER")
class ThreadIdGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Any> {
    override fun generate() = THREAD_ID_TOKEN
}

internal val THREAD_ID_TOKEN = Any()

/**
 * Extensible range-based random generator.
 * Takes min and max inclusive bounds of range and starts generating values from range of single value: the middle of given interval.
 * Then with constant probability expands current range from side to side until in reaches minValueInclusive and maxValueInclusive bounds.
 *
 * @param random   random for this generator
 * @param minValueInclusive minimal range left bound
 * @param maxValueInclusive   maximal range right bound
 */
class ExpandingRangeIntGenerator(
    private val random: Random,
    private val minValueInclusive: Int,
    private val maxValueInclusive: Int
) {

    /**
     * Current left (inclusive) bound of current range
     */
    private var currentStartInclusive: Int

    /**
     * Current right (inclusive) bound of current range
     */
    private var currentEndInclusive: Int

    private var expansionDirection = UP

    init {
        val firstValue = calculateFirstValue()
        currentStartInclusive = firstValue
        currentEndInclusive = firstValue
    }

    /**
     * Reset bounds of this expanding range to single value range of middle value of min and max bounds
     */
    fun resetRange() {
        val firstValue = calculateFirstValue()
        currentStartInclusive = firstValue
        currentEndInclusive = firstValue
        expansionDirection = UP
    }

    /**
     * Generates next random number.
     * With 50% percent probability expands current range if can and returns an expanded bound as a value.
     * It alternates expansion direction from left to right.
     * Otherwise, returns a random value from the current range.
     *
     * @return random value from current range or moved bound
     */
    fun nextInt(): Int {
        if (expansionDirection == DISABLED || random.nextDouble() < 0.65) {
            return generateFromRandomRange(currentStartInclusive, currentEndInclusive)
        }
        val value = if (expansionDirection == DOWN) {
            --currentStartInclusive
        } else {
            ++currentEndInclusive
        }
        expansionDirection = nextExpansionDirection()

        return value
    }

    private fun nextExpansionDirection(): NextExpansionDirection {
        if (currentStartInclusive == minValueInclusive && currentEndInclusive == maxValueInclusive) {
            return DISABLED
        }
        if (currentStartInclusive == minValueInclusive) {
            return UP
        }
        if (currentEndInclusive == maxValueInclusive) {
            return DOWN
        }

        return if (expansionDirection == UP) DOWN else UP
    }

    private enum class NextExpansionDirection {
        UP,
        DOWN,
        DISABLED
    }

    private fun generateFromRandomRange(rangeLowerBoundInclusive: Int, rangeUpperBoundInclusive: Int): Int {
        return rangeLowerBoundInclusive + random.nextInt(rangeUpperBoundInclusive - rangeLowerBoundInclusive + 1)
    }

    private fun calculateFirstValue() = ((minValueInclusive.toLong() + maxValueInclusive.toLong()) / 2).toInt()
}

/**
 * Factory method to create extensible range with string configuration
 *
 * @param random            random for this generator
 * @param configuration     string configuration of format startInclusive:endInclusive
 * @param minValueInclusive minimal range left bound (inclusive)
 * @param maxValueInclusive   maximal range right bound (inclusive)
 * @param type              type of generator which is using this method to throw exception with this type name im message
 */
private fun ExpandingRangeIntGenerator(
    random: Random, configuration: String, minValueInclusive: Int, maxValueInclusive: Int, type: String
): ExpandingRangeIntGenerator {
    if (configuration.isEmpty()) return ExpandingRangeIntGenerator(
        random = random,
        minValueInclusive = minValueInclusive,
        maxValueInclusive = maxValueInclusive
    )
    // minValueInclusive:maxValueInclusive
    val args = configuration.replace("\\s", "").split(":")

    require(args.size == 2) { "Configuration should have two arguments (start and end) separated by colon" }

    val startInclusive = args[0].toIntOrNull() ?: error("Bad $type configuration. StartInclusive value must be a valid integer.")
    val endInclusive = args[1].toIntOrNull() ?: error("Bad $type configuration. EndInclusive value must be a valid integer.")

    require(startInclusive >= minValueInclusive || endInclusive <= maxValueInclusive) { "Illegal range for $type type: [$startInclusive; $endInclusive)" }
    require(maxValueInclusive >= minValueInclusive) { "maxEnd must be >= than minStart" }
    require(endInclusive <= maxValueInclusive) { "end must be <= than maxEnd" }

    return ExpandingRangeIntGenerator(
        random = random,
        minValueInclusive = startInclusive,
        maxValueInclusive = endInclusive
    )
}

/**
 * Used to provide [java.util.Random] with different seeds to parameters generators and method generator
 * Is being created every time on each test to make an execution deterministic.
 */
class RandomProvider {

    private val seedGenerator = Random(SEED_GENERATOR_SEED)

    fun createRandom(): Random = Random(seedGenerator.nextLong())

    companion object {
        private const val SEED_GENERATOR_SEED = 0L
    }
}