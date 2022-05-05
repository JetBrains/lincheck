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

package org.jetbrains.kotlinx.lincheck.test.guide

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.*

class DataHolder {
    var first: Int = 42
    var second: Int = 7
    @Volatile var version = 0

    fun update(newFirst: Int, newSecond: Int) { // single thread updater
        version++ // lock the holder for reads
        first = newFirst
        second = newSecond
        version++ // release the holder for reads
    }

    fun read(): Pair<Int, Int> {
        while(true) {
            val curVersion = version
            // Is there a concurrent update?
            if (curVersion % 2 == 1) continue
            // Read the data
            val first = this.first
            val second = this.second
            // Return if version is the same
            if (curVersion == version) return first to second
        }
    }

    fun updateBlocking(newFirst: Int, newSecond: Int) = synchronized(this) {
        first = newFirst
        second = newSecond
    }

    fun readBlocking(): Pair<Int, Int> = synchronized(this) {
        val first = this.first
        val second = this.second
        return first to second
    }
}

@OpGroupConfig(name = "writer", nonParallel = true)
class DataHolderTest {
    private val dataHolder = DataHolder()

    @Operation(group = "writer")
    fun update(first: Int, second: Int) = dataHolder.update(first, second)

    @Operation
    fun read() = dataHolder.read()

    // @Test TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun modelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .check(this::class)
}