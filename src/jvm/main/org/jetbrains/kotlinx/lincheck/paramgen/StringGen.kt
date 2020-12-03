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
package org.jetbrains.kotlinx.lincheck.paramgen

import java.util.*

class StringGen(configuration: String) : ParameterGenerator<String?> {
    private val random = Random(0)
    private var maxWordLength = 0
    private var alphabet: String? = null
    override fun generate(): String {
        val cs = CharArray(random.nextInt(maxWordLength))
        for (i in cs.indices) cs[i] = alphabet!![random.nextInt(alphabet!!.length)]
        return String(cs)
    }

    companion object {
        private const val DEFAULT_MAX_WORD_LENGTH = 15
        private const val DEFAULT_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_ "
    }

    init {
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
                alphabet = configuration.substring(firstCommaIndex + 1)
            }
        }
    }
}