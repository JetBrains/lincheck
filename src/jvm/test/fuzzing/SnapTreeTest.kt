/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package fuzzing

import fuzzing.SnapTree.SnapTreeMap
import fuzzing.utils.AbstractConcurrentMapTest
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.scenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test
import kotlin.reflect.jvm.jvmName

class SnapTreeTest : AbstractConcurrentMapTest<SnapTreeMap<Long, Int>>(SnapTreeMap()) {
    //@Test(expected = AssertionError::class)
    fun knownFailingTest() {
        val scenario = scenario {
            initial {
                actor(this@SnapTreeTest::getOrPut, 4L, 4)
            }
            parallel {
                thread {
                    actor(this@SnapTreeTest::firstKey)
                }
                thread {
                    actor(this@SnapTreeTest::getOrPut, 1L, 7)
                    actor(this@SnapTreeTest::remove, 4L)
                }
                thread {
                    actor(this@SnapTreeTest::getOrPut, 5L, 6)
                }
            }
        }

        ModelCheckingOptions()
            .addCustomScenario(scenario)
            .invocationsPerIteration(1_000_000)
            .iterations(0)
            .logLevel(LoggingLevel.INFO)
            .check(this::class)
    }

    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() =
        coverageConfigurationForModelChecking(
            listOf(
                AbstractConcurrentMapTest::class.jvmName,
                SnapTreeTest::class.jvmName
            ),
            emptyList()
        )

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() =
        coverageConfigurationForFuzzing(
            listOf(
                AbstractConcurrentMapTest::class.jvmName,
                SnapTreeTest::class.jvmName
            ),
            emptyList()
        )

//    override fun <O : Options<O, *>> O.customize() {
//        //logLevel(LoggingLevel.INFO)
//        withCoverage(CoverageOptions(
//            excludePatterns = listOf(
//                SnapTreeTest::class.jvmName,
//                AbstractConcurrentMapTest::class.jvmName
//            ),
//            fuzz = true
//        ) { pr, res ->
//            println("Coverage: edges=${pr.toCoverage().coveredBranchesCount()}, " +
//                    "branch=${res.branchCoverage}/${res.totalBranches}, " +
//                    "line=${res.lineCoverage}/${res.totalLines}")
//        })
//    }


    // this operation is disabled for SnapTree, since it's bugged even in the sequential case
    override fun removeIf(key: Long, value: Int): Boolean {
        return false
    }

    @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
    fun firstKey(): Long = map.firstKey()

    @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
    fun lastKey(): Long = map.lastKey()

    @Operation
    fun lowerKey(@Param(name = "key") key: Long): Long? = map.lowerKey(key)

    @Operation
    fun higherKey(@Param(name = "key") key: Long): Long? = map.higherKey(key)
}