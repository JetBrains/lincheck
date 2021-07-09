/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package verifier.linearizability

import AbstractLincheckStressTest
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*

class HashMapTest : AbstractLincheckStressTest<HashMapTest>(IncorrectResultsFailure::class, UnexpectedExceptionFailure::class) {
    private val m = HashMap<Int, Int>()

    fun put(key: Int, value: Int): Int? = m.put(key, value)

    operator fun get(key: Int?): Int? = m[key]

    override fun <T : LincheckStressConfiguration<HashMapTest>> T.customize() {
        initialState { HashMapTest() }

        val keyGen = IntGen("")

        operation(IntGen(""), keyGen, HashMapTest::put)
        operation(keyGen, HashMapTest::get)
    }

    override fun extractState(): Any = m
}

