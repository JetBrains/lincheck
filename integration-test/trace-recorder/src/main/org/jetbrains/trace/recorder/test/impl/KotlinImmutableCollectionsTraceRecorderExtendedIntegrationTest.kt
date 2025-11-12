/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.impl

import org.jetbrains.trace.recorder.test.runner.AbstractTraceRecorderIntegrationTest
import org.jetbrains.trace.recorder.test.runner.ExtendedTraceRecorderTest
import org.jetbrains.trace.recorder.test.runner.loadResourceText
import org.jetbrains.trace.recorder.test.runner.parseJsonEntries
import org.jetbrains.trace.recorder.test.runner.transformEntriesToArray
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Paths

@RunWith(Enclosed::class)
class KotlinImmutableCollectionsTraceRecorderExtendedIntegrationTest {
    @Category(ExtendedTraceRecorderTest::class)
    @RunWith(Parameterized::class)
    class Parametrized(
        private val testClassName: String,
        private val testMethodName: String,
        private val gradleCommand: String,
        private val perEntryJvmArgs: List<String>,
        private val perEntryCheckRepresentation: Boolean,
    ) : AbstractTraceRecorderIntegrationTest() {
        override val projectPath: String = Paths.get("build", "integrationTestProjects", "kotlinx.collections.immutable").toString()

        @Test(timeout = 10 * 60 * 1000L)
        fun runKotlinImmutableCollectionsTest() = runTest(
            testClassName = testClassName,
            testMethodName = testMethodName,
            commands = listOf(gradleCommand),
            extraJvmArgs = perEntryJvmArgs,
            checkRepresentation = perEntryCheckRepresentation,
        )

        companion object {
            @JvmStatic
            @Parameterized.Parameters(name = "{index}: {0}::{1}")
            fun data(): Collection<Array<Any>> {
                val json = loadResourceText(
                    "/integrationTestData/kotlinxImmutableCollectionsTests.json",
                    KotlinImmutableCollectionsTraceRecorderExtendedIntegrationTest::class.java
                )
                val entries = parseJsonEntries(json)
                return entries.transformEntriesToArray()
            }
        }
    }
}