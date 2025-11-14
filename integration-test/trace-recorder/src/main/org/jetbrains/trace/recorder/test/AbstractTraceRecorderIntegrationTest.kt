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

import AbstractTraceIntegrationTest
import org.junit.Rule
import org.junit.rules.Timeout
import withStdErrTee
import java.util.concurrent.TimeUnit

abstract class AbstractTraceRecorderIntegrationTest : AbstractTraceIntegrationTest() {
    override val fatJarName: String = "trace-recorder-fat.jar"
    open val formatArgs: Map<String, String> = mapOf(
        "format" to "text",
        "formatOption" to "verbose",
    )

    private val teamCityAgentCpuBenchmark = System.getProperty("teamcity.agent.cpuBenchmark")?.toInt()
    val minutes = teamCityAgentCpuBenchmark?.let { (12000.0 / it).toInt() } ?: 10

    @Rule
    @JvmField
    val timeout = Timeout(minutes.toLong(), TimeUnit.MINUTES)

    init {
        println("Timeout: $minutes minutes")
    }

    public final override fun runGradleTest(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        gradleCommands: List<String>,
        checkRepresentation: Boolean,
        testNameSuffix: String?,
        onStdErrOutput: (String) -> Unit,
    ) {
        val (_, output) = withStdErrTee {
            runGradleTestImpl(
                testClassName,
                testMethodName,
                extraJvmArgs + listOf(
                    "-Dlincheck.traceRecorderMode=true",
                    "-XX:+UnlockExperimentalVMOptions",
                    "-XX:hashCode=2",
                ),
                extraAgentArgs + formatArgs,
                gradleCommands,
                checkRepresentation,
                testNameSuffix,
            )
        }
        onStdErrOutput(output)
    }
}
