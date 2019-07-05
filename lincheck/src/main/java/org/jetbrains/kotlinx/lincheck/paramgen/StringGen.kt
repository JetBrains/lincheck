package org.jetbrains.kotlinx.lincheck.paramgen

import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator.UNIQUE_MODIFIER
import java.lang.IllegalArgumentException
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

class StringGen(configuration: String) : ParameterGenerator<String> {
    private val genImpl: ParameterGenerator<String>

    init {
        val args = configuration.replace("\\s".toRegex(), "").split(":".toRegex()).filter { it.isNotEmpty() }

        genImpl = when {
            args.isEmpty() -> StringRandomizedGen(DEFAULT_MAX_WORD_LENGTH, DEFAULT_ALPHABET)
            args.size == 1 && args[0] == UNIQUE_MODIFIER -> StringUniqueGen()
            args.size == 1 -> StringRandomizedGen(args[0].toInt(), DEFAULT_ALPHABET)
            args.size == 2 -> StringRandomizedGen(args[0].toInt(), args[1])
            else -> throw IllegalArgumentException("There should be zero arguments or '$UNIQUE_MODIFIER' " +
                    "or one argument (max word length) " +
                    "or two arguments (max word length and alphabet) separated with colon")
        }
    }

    override fun generate(): String = genImpl.generate()

    override fun reset() {
        genImpl.reset()
    }

    private class StringRandomizedGen(private val maxWordLength: Int, private val alphabet: String) : ParameterGenerator<String> {
        private val random = Random(0)

        override fun generate(): String {
            val cs = CharArray(random.nextInt(maxWordLength))
            for (i in cs.indices)
                cs[i] = alphabet[random.nextInt(alphabet.length)]
            return String(cs)
        }
    }

    private class StringUniqueGen : ParameterGenerator<String> {
        private val intGen = IntGen(UNIQUE_MODIFIER)

        override fun generate(): String = intGen.generate().toString()

        override fun reset() = intGen.reset()
    }

    companion object {
        private const val DEFAULT_MAX_WORD_LENGTH = 15
        private const val DEFAULT_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_ "
    }
}



