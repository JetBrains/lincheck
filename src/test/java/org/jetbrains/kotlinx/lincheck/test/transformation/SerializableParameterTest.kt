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
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import java.io.Serializable
import java.util.concurrent.atomic.*

@Param(name = "key", gen = ValueHolderGen::class)
class SerializableParameterTest : AbstractLincheckTest() {
    private val counter = AtomicInteger(0)

    @Operation
    fun operation(@Param(name = "key") key: ValueHolder): Int = counter.addAndGet(key.value)

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }

    override fun extractState(): Any = counter.get()

    class ValueHolder(val value: Int) : Serializable
}

class ValueHolderGen(conf: String) : ParameterGenerator<SerializableParameterTest.ValueHolder> {
    override fun generate(): SerializableParameterTest.ValueHolder {
        return listOf(SerializableParameterTest.ValueHolder(1), SerializableParameterTest.ValueHolder(2)).random()
    }
}
