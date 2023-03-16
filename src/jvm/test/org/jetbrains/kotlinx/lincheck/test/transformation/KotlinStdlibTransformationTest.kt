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
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest

/**
 * This test checks that some methods in kotlin stdlib related to
 * `java.util` are correctly transformed.
 */
class KotlinStdlibTransformationTest : AbstractLincheckTest() {
    var hashCode = 0


    @Operation
    fun operation() {
        val intArray = IntArray(2)
        val intRange = (1..2)
        val objectArray = Array(2) { "" }
        val objectList: List<String> = listOf("a", "b")
        intArray.iterator()
        intArray.forEach {
            // do something
            hashCode = it.hashCode()
        }
        objectArray.iterator()
        objectArray.forEach {
            // do something
            hashCode = it.hashCode()
        }
        val it = intRange.iterator()
        while (it.hasNext()) {
            it.nextInt()
        }
        intRange.forEach {
            // do something
            hashCode = it.hashCode()
        }
        for (string in objectList) {
            // do something
            hashCode = string.hashCode()
        }
        intArray.toList().toTypedArray()
        objectArray.toList().toTypedArray()
        intArray.toHashSet().sum()
        objectArray.toSortedSet()
        intRange.toSet()
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        (this as? ModelCheckingOptions)?.verboseTrace()
    }

    override fun extractState(): Any = 0 // constant state
}
