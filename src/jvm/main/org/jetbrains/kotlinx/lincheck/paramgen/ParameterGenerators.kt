/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.paramgen

import org.jetbrains.kotlinx.lincheck.RandomProvider
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import java.util.*

/**
 * The implementation of this interface is used to generate parameters
 * for [operation][Operation].
 *
 * Now all successors must have constructor with [RandomProvider] and config [String].
 */
interface ParameterGenerator<T> {
    fun generate(): T

    /**
     * Resets current range bounds to start, if this generator has such expanding range.
     * Meanwhile, it shouldn't reset random to avoid undesired correlation between scenarios.
     */
    fun reset()
}

/**
 * Used only as a default value in [Operation] annotation, as it's impossible to use `null` as a default value in Java
 */
internal object Dummy : ParameterGenerator<Any?> {
    override fun generate(): Any {
        throw UnsupportedOperationException()
    }

    override fun reset() {}
}

class IntGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Int> {
    private val generator: ExpandingRangeIntGenerator

    init {
        generator = ExpandingRangeIntGenerator(
            random = randomProvider.createRandom(),
            configuration = configuration,
            minStartInclusive = Int.MIN_VALUE,
            maxEndInclusive = Int.MAX_VALUE,
            type = "int"
        )
    }

    override fun generate(): Int = generator.nextInt()
    override fun reset() = generator.reset()
}

class BooleanGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Boolean> {
    private val random = randomProvider.createRandom()

    override fun generate() = random.nextBoolean()
    override fun reset() {
    }
}

class ByteGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Byte> {
    private val generator: ExpandingRangeIntGenerator

    init {
        generator = ExpandingRangeIntGenerator(
            random = randomProvider.createRandom(),
            configuration = configuration,
            minStartInclusive = Byte.MIN_VALUE.toInt(),
            maxEndInclusive = Byte.MAX_VALUE.toInt(),
            type = "byte"
        )
    }

    override fun generate(): Byte = generator.nextInt().toByte()

    override fun reset() = generator.reset()
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
            begin = DEFAULT_BEGIN.toDouble()
            end = DEFAULT_END.toDouble()
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
            startInclusive = maxSteps / 2,
            endInclusive = maxSteps / 2,
            minStartInclusive = 0,
            maxEndInclusive = maxSteps
        )

        this.step = step
        this.begin = begin
    }

    override fun generate(): Double = begin + step * intGenerator.nextInt()

    override fun reset() = intGenerator.reset()

    companion object {
        private const val DEFAULT_STEP = 0.1f
        private const val DEFAULT_BEGIN = -10f
        private const val DEFAULT_END = 10f
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

class ShortGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Short> {
    private val generator: ExpandingRangeIntGenerator = ExpandingRangeIntGenerator(
        random = randomProvider.createRandom(),
        configuration = configuration,
        minStartInclusive = Byte.MIN_VALUE.toInt(),
        maxEndInclusive = Byte.MAX_VALUE.toInt(),
        type = "byte"
    )

    override fun generate(): Short = generator.nextInt().toShort()

    override fun reset() = generator.reset()
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
        if (currentWordLength < maxWordLength && random.nextBoolean()) {
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
class ThreadIdGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Any> {
    override fun generate() = THREAD_ID_TOKEN

    override fun reset() {
    }
}

internal val THREAD_ID_TOKEN = Any()
