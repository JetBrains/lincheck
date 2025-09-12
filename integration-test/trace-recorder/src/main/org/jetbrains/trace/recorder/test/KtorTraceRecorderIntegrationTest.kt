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

import org.junit.Ignore
import org.junit.Test
import java.nio.file.Paths

class KtorTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "ktor").toString()
    
    private fun runKtorTests(vararg submodules: String, checkRepresentation: Boolean = false) {
        require(submodules.isNotEmpty()) { "At least one submodule should be specified" }
        val submodulePath = submodules.joinToString("-")
        val rootPath = (1..submodules.size).joinToString("/", prefix = "$projectPath/", postfix = "/build") {
            "ktor-${submodules.take(it).joinToString("-")}"
        }
        runKtorTestsImpl(submodulePath, rootPath, checkRepresentation)
    }

    private fun runKtorTestsImpl(submodulePath: String, rootPath: String, checkRepresentation: Boolean = false) {
        runGradleTests(
            gradleBuildCommands = listOf(":ktor-$submodulePath:compileTestKotlinJvm"),
            gradleTestCommands = listOf(":ktor-$submodulePath:cleanJvmTest", ":ktor-$submodulePath:jvmTest"),
            rootPath = rootPath,
            checkRepresentation = checkRepresentation,
        )
    }

    @Test
    @Ignore("No output")
    fun `ktor-http`() = runKtorTests("http")

    @Test
    @Ignore("Verify error")
    fun `ktor-http-cio`() = runKtorTests("http", "cio") 

    @Test
    @Ignore("Out of bounds")
    fun `ktor-io`() = runKtorTests("io")

    @Test
    @Ignore("Inline method error")
    fun `ktor-network`() = runKtorTests("network")

    @Test
    @Ignore("No output")
    fun `ktor-network-tls`() = runKtorTests("network", "tls")

    @Test
    fun `ktor-network-tls-certificates`() = runKtorTests("network", "tls", "certificates")

    @Test
    fun `ktor-htmx-html`() = runKtorTestsImpl(
        submodulePath = "htmx-html",
        rootPath = "${projectPath}/ktor-shared/ktor-htmx/ktor-htmx-html/build",
    )

    @Test
    @Ignore("Inline method error")
    fun `ktor-utils`() = runKtorTests("utils")

    @Test
    @Ignore("Verify error")
    fun `ktor-server-cio`() = runKtorTests("server", "cio")
}
