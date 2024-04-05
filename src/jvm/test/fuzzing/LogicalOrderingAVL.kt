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

import fuzzing.LogicalOrderingAVL.LogicalOrderingAVL
import fuzzing.utils.IntIntAbstractConcurrentMapTest
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.coverage.CoverageOptions
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.toCoverage
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test
import kotlin.reflect.jvm.jvmName

class LogicalOrderingAVLTest : IntIntAbstractConcurrentMapTest<LogicalOrderingAVL<Int, Int>>(LogicalOrderingAVL()) {
//    override fun <O : Options<O, *>> O.customize() {
//        withCoverage(CoverageOptions(
//            excludePatterns = listOf(
//                LogicalOrderingAVLTest::class.jvmName,
//                IntIntAbstractConcurrentMapTest::class.jvmName
//            ),
//            fuzz = true
//        ) { pr, res ->
//            println("Coverage: edges=${pr.toCoverage().coveredBranchesCount()}, " +
//                    "branch=${res.branchCoverage}/${res.totalBranches}, " +
//                    "line=${res.lineCoverage}/${res.totalLines}")
//        })
//    }

    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() =
        coverageConfigurationForModelChecking(
            listOf(
                IntIntAbstractConcurrentMapTest::class.jvmName,
                LogicalOrderingAVLTest::class.jvmName
            ),
            emptyList()
        )

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() =
        coverageConfigurationForFuzzing(
            listOf(
                IntIntAbstractConcurrentMapTest::class.jvmName,
                LogicalOrderingAVLTest::class.jvmName
            ),
            emptyList()
        )
}