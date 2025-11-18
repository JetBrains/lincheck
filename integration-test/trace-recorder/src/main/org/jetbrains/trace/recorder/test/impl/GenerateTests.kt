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

import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolute
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText

private val testClassGenerators = TestGenerator::class.sealedSubclasses.map {
    it.objectInstance ?: error("TestGenerator class must be an object but ${it.qualifiedName} is not")
}

private val basePath: Path by lazy {
    val currentModuleSubpath = Paths.get("integration-test", "trace-recorder")
    if (currentModuleSubpath.exists()) currentModuleSubpath else Paths.get("")
}

private val testPath = basePath / "src" / "main" / "org" / "jetbrains" / "trace" / "recorder" / "test" / "impl" / "generated"

private val TestGenerator.testFilePath: Path get() = testPath / "${groupName}.kt"

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
class TestGeneratedDataCorrectness {
    @Test
    fun test() {
        for (testClass in testClassGenerators) {
            val testFilePath = testClass.testFilePath.absolute()
            if (!testFilePath.exists()) {
                Assert.fail("File $testFilePath does not exist")
            }
            val expected = testClass.generateString()
            val actual = testFilePath.readText()
            assertEquals(expected, actual)
        }
    }
}
