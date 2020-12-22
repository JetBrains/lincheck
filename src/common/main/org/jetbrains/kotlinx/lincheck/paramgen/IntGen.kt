/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

class IntGen(configuration: String) : ParameterGenerator<Int> {
    private val random = Random(0)
    private var begin = 0
    private var end = 0

    init {
        if (configuration.isEmpty()) { // use default configuration
            begin = DEFAULT_BEGIN
            end = DEFAULT_END
        } else {
            val args = configuration.replace("\\s".toRegex(), "").split(":".toRegex()).toTypedArray()
            when (args.size) {
                2 -> {
                    begin = args[0].toInt()
                    end = args[1].toInt()
                }
                else -> throw IllegalArgumentException("Configuration should have " +
                        "two arguments (begin and end) separated by colon")
            }
        }
    }

    override fun generate(): Int {
        return begin + random.nextInt(end - begin + 1)
    }

    fun checkRange(min: Int, max: Int, type: String) {
        require(!(begin < min || end - 1 > max)) {
            ("Illegal range for "
                    + type + " type: [" + begin + "; " + end + ")")
        }
    }
}

private const val DEFAULT_BEGIN = -10
private const val DEFAULT_END = 10