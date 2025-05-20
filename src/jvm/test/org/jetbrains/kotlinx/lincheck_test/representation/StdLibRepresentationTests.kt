/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ThreadSafeCollectionMutedRepresentationTest: BaseTraceRepresentationTest("thread_safe_collection_muted") {
    
    private val concurrentMap = ConcurrentHashMap<Int, Int>()
    private var a = 1
    
    override fun operation() {
        concurrentMap.put(1, 1)
        a++
    }
}

class ThreadSafeCollectionMutedLambdaRepresentationTest: BaseTraceRepresentationTest("thread_safe_collection_muted_lambda") {

    private val concurrentMap = ConcurrentHashMap<Int, Int>()
    private val innerConcurrentMap = ConcurrentHashMap<Int, Int>()
    private var a = 1

    override fun operation() {
        concurrentMap.computeIfAbsent(1) { 
            a = 5
            innerConcurrentMap.computeIfAbsent(1) { ++a }
            ++a 
        }
        a++
    }
}

class ThreadSafeCollectionUnmutedRepresentationTest: BaseTraceRepresentationTest("thread_safe_collection_unmuted") {
    private val concurrentMap = ConcurrentHashMap<Int, Int>()
    private var a = 1

    override fun operation() {
        concurrentMap.put(1, 1)
        a++
    }

    override fun ModelCheckingOptions.customize() {
        analyzeStdLib(true)
    }
}

class UnsafeCollectionSwitchPointTest {
    private val hm = HashMap<Int, Int>()
    
    @Operation
    fun put1() = hm.put(1, 1)

    @Operation
    fun put2() = hm.put(1, 2)
    
    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::put1) }
                thread { actor(::put2) }
            }
        }
        .iterations(0)
        .checkImpl(this::class.java) { failure ->
            failure.checkLincheckOutput("unsafe_collection_switch_point")
        }
    
}

class UnsafeCollectionNoSwitchPointTest {
    private val hm = HashMap<Int, Int>()

    @Operation
    fun put1() = hm.put(1, 1)

    @Operation
    fun put2() = hm.put(1, 2)

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::put1) }
                thread { actor(::put2) }
            }
        }
        .addGuarantee(forClasses("java.util.Map", "java.util.HashMap").allMethods().mute())
        .iterations(0)
        .checkImpl(this::class.java) { failure ->
            check(failure == null) { "Test should pass" }
        }
}
