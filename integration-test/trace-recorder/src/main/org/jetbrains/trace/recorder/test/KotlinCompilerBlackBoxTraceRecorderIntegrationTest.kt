/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test

import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.createTempFile

@RunWith(Enclosed::class)
class KotlinCompilerBlackBoxTraceRecorderIntegrationTest {
    @Category(ExtendedTraceRecorderTest::class)
    @RunWith(Parameterized::class)
    class Parametrized(
        private val testClassName: String,
        private val testMethodName: String,
        private val gradleCommand: String,
        private val perEntryJvmArgs: List<String>,
        private val perEntryCheckRepresentation: Boolean,
    ) : AbstractTraceRecorderIntegrationTest() {
        override val projectPath: String = Paths.get("build", "integrationTestProjects", "kotlin").toString()
        override val formatArgs: Map<String, String> = mapOf("format" to "binary", "formatOption" to "stream")

        @Test(timeout = 10 * 60 * 1000L)
        fun runKotlinCompilerTest() = runKotlinCompilerTestImpl(
            testClassName, testMethodName, gradleCommand, perEntryJvmArgs, perEntryCheckRepresentation
        )

        companion object {
            @JvmStatic
            @Parameterized.Parameters(name = "{index}: {0}::{1}")
            fun data(): Collection<Array<Any>> {
                val json = loadResourceText(
                    "/integrationTestData/kotlinCompilerTests.json",
                    KotlinCompilerBlackBoxTraceRecorderIntegrationTest::class.java
                )
                val entries = parseJsonEntries(json)
                return entries.transformEntriesToArray()
            }
        }
    }
}

private fun AbstractTraceRecorderIntegrationTest.runKotlinCompilerTestImpl(
    testClassName: String,
    testMethodName: String,
    gradleCommand: String,
    jvmArgs: List<String>,
    checkRepresentation: Boolean,
) {
    withPermissions { permissions ->
        val allJvmArgs = listOf("-Djava.security.policy==${permissions.absolutePath}") + jvmArgs
        runGradleTest(
            testClassName = testClassName,
            testMethodName = testMethodName,
            extraJvmArgs = allJvmArgs,
            gradleCommands = listOf(gradleCommand),
            checkRepresentation = checkRepresentation,
        )
    }
}

private fun <T> withPermissions(block: (File) -> T): T {
    val permissions = createTempFile("permissions", "txt").toFile()
    return try {
        permissions.writeText(
            """
                    grant codeBase "file:/-" {
                        permission java.security.AllPermission;
                    };
                """.trimIndent()
        )
        block(permissions)
    } finally {
        permissions.delete()
    }
}
