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

import fuzzing.utils.AbstractConcurrentMapTest
import fuzzing.utils.AbstractFuzzerBenchmarkTest
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import kotlin.reflect.jvm.jvmName

class MutexTest : AbstractFuzzerBenchmarkTest() {
    private val mutex = Mutex()

    override fun <O : Options<O, *>> O.customize() {
        iterations(40)
        threads(2)
        actorsBefore(1)
        actorsPerThread(3)
        actorsAfter(0)
        logLevel(LoggingLevel.INFO)
        minimizeFailedScenario(false)
        if (this is ModelCheckingOptions) checkObstructionFreedom()
    }

    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() =
        coverageConfigurationForModelChecking(
            listOf(
                MutexTest::class.jvmName
            ),
            listOf("kotlinx\\.coroutines.*")
        )

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() =
        coverageConfigurationForFuzzing(
            listOf(
                MutexTest::class.jvmName
            ),
            listOf("kotlinx\\.coroutines.*")
        )

//    @Test
//    fun modelCheckingTestLong() {
//        ModelCheckingOptions().apply {
//            invocationsPerIteration(10000)
//            iterations(100)
//            threads(2)
//            actorsBefore(1)
//            actorsPerThread(4)
//            actorsAfter(0)
//            minimizeFailedScenario(false)
//            logLevel(LoggingLevel.INFO)
//            checkObstructionFreedom()
//            withCoverage(CoverageOptions(
//                excludePatterns = listOf(MutexTest::class.jvmName),
//                includePatterns = listOf("kotlinx\\.coroutines.*"),
//                fuzz = true
//            ) { pr, res ->
//                println("Coverage: edges=${pr.toCoverage().coveredBranchesCount()}, " +
//                        "branch=${res.branchCoverage}/${res.totalBranches}, " +
//                        "line=${res.lineCoverage}/${res.totalLines}")
//            })
//            check(this@MutexTest::class)
//        }
//    }


    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun unlock() = mutex.unlock()

    @Operation
    fun tryLock() = mutex.tryLock()

    @Operation(promptCancellation = true)
    suspend fun lock() = mutex.lock()
}