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

import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory
import com.googlecode.concurrenttrees.suffix.ConcurrentSuffixTree
import fuzzing.utils.AbstractConcurrentMapTest
import fuzzing.utils.AbstractFuzzerBenchmarkTest
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.coverage.CoverageOptions
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.toCoverage
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.scenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test
import kotlin.reflect.jvm.jvmName

@Param(name = "key", gen = StringGen::class, conf = "4:ab")
class ConcurrentSuffixTreeTest : AbstractFuzzerBenchmarkTest() {
    private val suffixTree = ConcurrentSuffixTree<Int>(DefaultCharArrayNodeFactory())

    //@Test(expected = AssertionError::class)
    fun knownFailingTest() {
        val scenario = scenario {
            parallel {
                thread {
                    actor(this@ConcurrentSuffixTreeTest::put, "baa", 5)
                }
                thread {
                    actor(this@ConcurrentSuffixTreeTest::getKeysContaining, "baa")
                    actor(this@ConcurrentSuffixTreeTest::getKeysContaining, "aa")
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


    override fun <O : Options<O, *>> O.customize() {
        threads(2)
        //logLevel(LoggingLevel.INFO)
    }


    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() =
        coverageConfigurationForModelChecking(
            listOf(
                ConcurrentSuffixTreeTest::class.jvmName
            ),
            emptyList()
        )

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() =
        coverageConfigurationForFuzzing(
            listOf(
                ConcurrentSuffixTreeTest::class.jvmName
            ),
            emptyList()
        )

//    @Test
//    fun modelCheckingTestLong() {
//        ModelCheckingOptions().apply {
//            invocationsPerIteration(10000)
//            iterations(10)
//            threads(2)
//            actorsPerThread(4)
//            // logLevel(LoggingLevel.INFO)
//            withCoverage(CoverageOptions(
//                excludePatterns = listOf(
//                    ConcurrentSuffixTreeTest::class.jvmName
//                ),
//                fuzz = true
//            ) { pr, res ->
//                println("Coverage: edges=${pr.toCoverage().coveredBranchesCount()}, " +
//                        "branch=${res.branchCoverage}/${res.totalBranches}, " +
//                        "line=${res.lineCoverage}/${res.totalLines}")
//            })
//            check(this@ConcurrentSuffixTreeTest::class)
//        }
//    }

    @Operation
    fun getKeysContaining(@Param(name = "key") key: String) =
        // ignore the order of output strings as it not important
        suffixTree.getKeysContaining(key).map { it.toString() }.sortedWith(String.CASE_INSENSITIVE_ORDER).toString()

    @Operation
    fun getKeysEndingWith(@Param(name = "key") key: String) =
        // ignore the order of output strings as it not important
        suffixTree.getKeysEndingWith(key).map { it.toString() }.sortedWith(String.CASE_INSENSITIVE_ORDER).toString()

    @Operation
    fun getValuesForKeysEndingWith(@Param(name = "key") key: String) =
        suffixTree.getValuesForKeysEndingWith(key).sorted().toString()

    @Operation
    fun getValueForExactKey(@Param(name = "key")key: String) = suffixTree.getValueForExactKey(key)

    @Operation
    fun put(@Param(name = "key") key: String, value: Int) = if (key.length != 0) suffixTree.put(key, value).toString() else 0
}