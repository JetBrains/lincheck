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
import org.jctools.maps.NonBlockingHashMapLong
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.Options
import kotlin.reflect.jvm.jvmName

class NonBlockingHashMapLongTest : AbstractConcurrentMapTest<NonBlockingHashMapLong<Int>>(NonBlockingHashMapLong()) {
    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() {
        logLevel(LoggingLevel.INFO)
        coverageConfigurationForModelChecking(
            listOf(
                AbstractConcurrentMapTest::class.jvmName,
                NonBlockingHashMapLongTest::class.jvmName
            ),
            emptyList()
        )
    }

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() =
        coverageConfigurationForFuzzing(
            listOf(
                AbstractConcurrentMapTest::class.jvmName,
                NonBlockingHashMapLongTest::class.jvmName
            ),
            emptyList()
        )
}