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
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.*

actual typealias SequentialSpecification<T> = Class<T>
actual fun <T : Any> SequentialSpecification<T>.getInitialState(): T = this.getDeclaredConstructor().newInstance()

fun <OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> Options<OPT, CTEST>.executionGenerator(executionGeneratorClass: Class<out ExecutionGenerator>): OPT {
    executionGenerator {
        testConfiguration, testStructure -> executionGeneratorClass.getConstructor(CTestConfiguration::class.java, CTestStructure::class.java)
        .newInstance(testConfiguration, testStructure)
    }
    return this as OPT
}

fun <OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> Options<OPT, CTEST>.verifier(verifierClass: Class<out Verifier>): OPT {
    verifier { sequentialSpecification ->
        verifierClass.getConstructor(SequentialSpecification::class.java).newInstance(sequentialSpecification)
    }
    return this as OPT
}