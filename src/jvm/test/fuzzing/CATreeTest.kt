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

import fuzzing.CATreeMapAVL.CATreeMapAVL
import fuzzing.utils.AbstractFuzzerBenchmarkTest
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.coverage.CoverageOptions
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.toCoverage
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.LongGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test
import kotlin.reflect.jvm.jvmName

// CATreeMapAVL does not implement ConcurrentMap interface,
// so we cannot re-use AbstractConcurrentMapTest here
@Param(name = "key", gen = LongGen::class, conf = "1:5")
@Param(name = "value", gen = IntGen::class, conf = "1:8")
class CATreeTest : AbstractFuzzerBenchmarkTest() {
    private val map = CATreeMapAVL<Long, Int>()


    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
        actorsAfter(0)
        minimizeFailedScenario(false)
        //logLevel(LoggingLevel.INFO)
    }


    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() =
        coverageConfigurationForModelChecking(
            listOf(
                CATreeTest::class.jvmName
            ),
            emptyList()
        )

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() {
        minimizeFailedScenario(false)
        coverageConfigurationForFuzzing(
            listOf(
                CATreeTest::class.jvmName
            ),
            emptyList()
        )
    }

    @Operation
    operator fun get(@Param(name = "key") key: Long): Int? = map.get(key)

    @Operation
    fun put(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Int? = map.put(key, value)

    @Operation
    fun remove(@Param(name = "key") key: Long): Int? = map.remove(key)

    @Operation
    fun containsKey(@Param(name = "key") key: Long): Boolean = map.containsKey(key)

    @Operation
    fun putIfAbsent(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Int? = map.putIfAbsent(key, value)

    @Operation
    fun size(): Int = map.size

    @Operation
    fun clear() = map.clear()

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .iterations(100)
        .minimizeFailedScenario(false)
        .threads(3)
        .actorsPerThread(4)
        .invocationsPerIteration(10000)
        .actorsBefore(0)
        .actorsAfter(0)
        .logLevel(LoggingLevel.INFO)
//        .withCoverage(CoverageOptions(
//            excludePatterns = listOf(this::class.jvmName),
//            fuzz = true
//        ) { pr, res ->
//            println("Coverage: edges=${pr.toCoverage().coveredBranchesCount()}, " +
//                    "branch=${res.branchCoverage}/${res.totalBranches}, " +
//                    "line=${res.lineCoverage}/${res.totalLines}")
//        })
        .check(this::class)
}