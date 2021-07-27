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

package org.jetbrains.kotlinx.lincheck.test.strategy.modelchecking

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.nvm.DurableModel
import org.jetbrains.kotlinx.lincheck.nvm.StrategyRecoveryOptions
import org.jetbrains.kotlinx.lincheck.nvm.SwitchesAndCrashesModelCheckingStrategy
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier
import org.junit.Assert
import org.junit.Test


class BruteForceNVMModelCheckingTest {

    @Test
    fun emptyTest() = test(EmptyMethods::class.java, 8)

    @Test
    fun oneWriteTest() = test(OneWriteMethod::class.java, 12)

    @Test
    fun twoWriteTest() = test(TwoWriteMethod::class.java, 16)

    @Test
    fun oneNVMWriteTest() = test(OneNVMWriteMethod::class.java, 14)

    @Test
    fun oneNVMWriteFLushTest() = test(OneNVMWriteFlushMethod::class.java, 22)

    @Test
    fun oneNVMFLushTest() = test(OneNVMFlushMethod::class.java, 12)

    fun test(clazz: Class<*>, result: Int) {
        val a = clazz.getMethod("a")
        val b = clazz.getMethod("b")
        val scenario = ExecutionScenario(emptyList(), mutableListOf(mutableListOf(Actor(a, mutableListOf())), mutableListOf(Actor(b, mutableListOf()))), emptyList())
        SingleScenario.scenario = scenario
        val invocations = 1000000
        val model = DurableModel(true, StrategyRecoveryOptions.MANAGED)
        val config = ModelCheckingCTestConfiguration(
            clazz, 1, 2, 1,
            0, 0, SingleScenario::class.java,
            LinearizabilityVerifier::class.java, false,
            101, invocations, emptyList(), true,
            false, clazz, 10_000, true,
            true, emptyList(), model
        )
        val strategy = SwitchesAndCrashesModelCheckingStrategy(config, clazz, scenario, emptyList(), null, LinearizabilityVerifier(clazz), model)
        Assert.assertNull(strategy.run())
        Assert.assertEquals(result, strategy.invocations())
    }
}


class SingleScenario(configuration: CTestConfiguration, testStructure: CTestStructure) : ExecutionGenerator(configuration, testStructure) {
    override fun nextExecution() = scenario

    companion object {
        lateinit var scenario: ExecutionScenario
    }
}

class EmptyMethods {
    fun a() = Unit
    fun b() = Unit
}

class OneWriteMethod {
    private var x: Int = 0
    fun a() {
        x = 1
    }

    fun b() = Unit
}

class TwoWriteMethod {
    private var x: Int = 0
    fun a() {
        x = 1
        x = 2
    }

    fun b() = Unit
}

class OneNVMWriteMethod {
    private var x = nonVolatile(0)
    fun a() {
        x.value = 1
    }

    fun b() = Unit
}

class OneNVMWriteFlushMethod {
    private var x = nonVolatile(0)
    fun a() {
        x.value = 1
        x.flush()
    }

    fun b() = Unit
}

class OneNVMFlushMethod {
    private var x = nonVolatile(0)
    fun a() {
        x.flush()
    }

    fun b() = Unit
}
