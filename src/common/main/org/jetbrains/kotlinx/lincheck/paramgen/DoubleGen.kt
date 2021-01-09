/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */
package org.jetbrains.kotlinx.lincheck.paramgen

import kotlin.random.Random

class DoubleGen(configuration: String) : ParameterGenerator<Double> {
    private val random = Random(0)
    private var begin = 0.0
    private var end = 0.0
    private var step = 0.0

    init {
        if (configuration.isEmpty()) { // use default configuration
            begin = DEFAULT_BEGIN.toDouble()
            end = DEFAULT_END.toDouble()
            step = DEFAULT_STEP.toDouble()
        } else {
            val args = configuration.replace("\\s".toRegex(), "").split(":".toRegex()).toTypedArray()
            when (args.size) {
                2 -> {
                    begin = args[0].toDouble()
                    end = args[1].toDouble()
                    step = DEFAULT_STEP.toDouble()
                }
                3 -> {
                    begin = args[0].toDouble()
                    step = args[1].toDouble()
                    end = args[2].toDouble()
                }
                else -> throw IllegalArgumentException("Configuration should have two (begin and end) " +
                        "or three (begin, step and end) arguments separated by colon")
            }
            require((end - begin) / step < Int.MAX_VALUE) { "step is too small for specified range" }
        }
    }

    override fun generate(): Double {
        val delta = end - begin
        if (step == 0.0) // step is not defined
            return begin + delta * random.nextDouble()
        val maxSteps = (delta / step).toInt()
        return begin + delta * random.nextInt(maxSteps + 1)
    }
}

private const val DEFAULT_BEGIN = -10f
private const val DEFAULT_END = 10f
private const val DEFAULT_STEP = 0.1f