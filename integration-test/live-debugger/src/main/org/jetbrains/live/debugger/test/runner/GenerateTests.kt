/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.live.debugger.test.runner

import org.jetbrains.trace.recorder.test.runner.TestGenerator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolute
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText

private val testClassGenerators: List<TestGenerator> = listOf(
    KtorLiveDebuggerJsonTests.Companion,
    KotlinxImmutableCollectionsLiveDebuggerJsonTests.Companion,
    KotlinCompilerLiveDebuggerJsonTests.Companion,
)

private val basePath: Path by lazy {
    val currentModuleSubpath = Paths.get("integration-test", "live-debugger")
    if (currentModuleSubpath.exists()) currentModuleSubpath else Paths.get("")
}

private val testPath = basePath / "src" / "main" / "org" / "jetbrains" / "live" / "debugger" / "test" / "impl" / "generated"

private val TestGenerator.testFilePath: Path get() = testPath / "${groupName}GeneratedTests.kt"

/**
 * Updates generated tests.
 */
fun main() {
    for (testClass in testClassGenerators) {
        testClass.generateFile(testClass.testFilePath)
    }
}

/**
 * Checks that generated tests are up to date.
 */
class LiveDebuggerTestGeneratedDataCorrectness {
    @Test
    fun test() {
        for (testClass in testClassGenerators) {
            val testFilePath = testClass.testFilePath.absolute()
            if (!testFilePath.exists()) {
                Assertions.fail<Unit>("File $testFilePath does not exist")
            }
            val expected = testClass.generateString()
            val actual = testFilePath.readText()
            assertEquals(expected, actual)
        }
    }
}
