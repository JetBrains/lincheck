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

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.lincheck.datastructures.forClasses
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ThreadSafeCollectionMutedRepresentationTest: BaseRunConcurrentRepresentationTest<Unit>("thread_safe_collection_muted") {
    
    private val concurrentMap = ConcurrentHashMap<Int, Int>()
    private var a = 1
    
    override fun block() {
        concurrentMap.put(1, 1)
        a++
        check(false)
    }
    override val analyzeStdLib: Boolean = false
}

class ThreadSafeCollectionUnmutedRepresentationTest: BaseRunConcurrentRepresentationTest<Unit>("thread_safe_collection_unmuted") {
    private val concurrentMap = ConcurrentHashMap<Int, Int>()
    private var a = 1

    override fun block() {
        concurrentMap.put(1, 1)
        a++
        check(false)
    }
    override val analyzeStdLib: Boolean = true
}

class ThreadSafeCollectionMutedLambdaRepresentationTest: BaseRunConcurrentRepresentationTest<Unit>("thread_safe_collection_muted_lambda") {

    private val concurrentMap = ConcurrentHashMap<Int, Int>()
    private val innerConcurrentMap = ConcurrentHashMap<Int, Int>()
    private var a = 1

    override fun block() {
        concurrentMap.computeIfAbsent(1) { 
            a = 5
            innerConcurrentMap.computeIfAbsent(1) { ++a }
            ++a 
        }
        a++
        check(false)
    }
    override val analyzeStdLib: Boolean = false
}

class ThreadSafeCollectionUnmutedLambdaRepresentationTest: BaseRunConcurrentRepresentationTest<Unit>("thread_safe_collection_unmuted_lambda") {
    private val concurrentMap = ConcurrentHashMap<Int, Int>()
    private val innerConcurrentMap = ConcurrentHashMap<Int, Int>()
    private var a = 1

    override fun block() {
        concurrentMap.computeIfAbsent(1) {
            a = 5
            innerConcurrentMap.computeIfAbsent(1) { ++a }
            ++a
        }
        a++
        check(false)
    }
    override val analyzeStdLib: Boolean = true
}

// TODO potentially switch to runConcurrentTest if #650 is fixed by #663
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
        .analyzeStdLib(false)
        .checkImpl(this::class.java) { failure ->
            failure.checkLincheckOutput("unsafe_collection_switch_point")
        }
    
}

// TODO potentially switch to runConcurrentTest if #650 is fixed by #663
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
        .analyzeStdLib(false)
        .checkImpl(this::class.java) { failure ->
            check(failure == null) { "Test should pass" }
        }
}
