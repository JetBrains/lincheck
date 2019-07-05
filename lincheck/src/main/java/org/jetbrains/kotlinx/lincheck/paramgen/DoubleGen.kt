package org.jetbrains.kotlinx.lincheck.paramgen

import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator.UNIQUE_MODIFIER
import kotlin.random.Random

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

class DoubleGen(configuration: String) : ParameterGenerator<Double> {

    private val genImpl: ParameterGenerator<Double>

    init {
        val args = configuration.replace("\\s".toRegex(), "").split(":".toRegex()).filter { it.isNotEmpty() }
        genImpl = when {
            args.isEmpty() -> DoubleRangeGen(DEFAULT_BEGIN..DEFAULT_END, DEFAULT_STEP)
            args.size == 1 && args[0] == UNIQUE_MODIFIER -> DoubleUniqueGen()
            args.size == 2 -> DoubleRangeGen(args[0].toDouble()..args[1].toDouble(), DEFAULT_STEP)
            args.size == 3 -> DoubleRangeGen(args[0].toDouble()..args[2].toDouble(), args[1].toDouble())
            else -> throw IllegalArgumentException("There should be zero arguments or '$UNIQUE_MODIFIER' " +
                    "or two (begin and end) or three (begin, step and end) arguments separated by comma" )
        }
    }

    override fun generate(): Double = genImpl.generate()

    override fun reset() = genImpl.reset()

    private class DoubleRangeGen(val range: ClosedRange<Double>, val step: Double) : ParameterGenerator<Double> {
        private val random = Random(0)

        override fun generate(): Double {
            val delta = range.endInclusive - range.start
            if (step == 0.0) // step is not defined
                return range.start + delta * random.nextDouble()
            val maxSteps = (delta / step).toInt()
            return range.start + step * random.nextInt(maxSteps + 1)
        }
    }

    private class DoubleUniqueGen : ParameterGenerator<Double> {
        private var nextToGenerate = 0

        override fun generate(): Double = nextToGenerate++.toDouble()

        override fun reset() {
            nextToGenerate = 0
        }
    }

    companion object {
        private const val DEFAULT_BEGIN = -10.0
        private const val DEFAULT_END = 10.0
        private const val DEFAULT_STEP = 0.1
    }
}
