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
import java.nio.file.Paths

@Category(ExtendedTraceRecorderTest::class)
class KtorTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "ktor").toString()
    override val formatArgs: Map<String, String> = mapOf("format" to "binary", "formatOption" to "stream")
    
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
    fun `ktor-http`() = runKtorTests("http")

    @Test
    fun `ktor-http-cio`() = runKtorTests("http", "cio") 

    @Test
    fun `ktor-io`() = runKtorTests("io")

    @Test
    fun `ktor-network`() = runKtorTests("network")

    @Test
    fun `ktor-network-tls`() = runKtorTests("network", "tls")

    @Test
    fun `ktor-network-tls-certificates`() = runKtorTests("network", "tls", "certificates")

    @Test
    fun `ktor-htmx-html`() = runKtorTestsImpl(
        submodulePath = "htmx-html",
        rootPath = "${projectPath}/ktor-shared/ktor-htmx/ktor-htmx-html/build",
    )

    @Test
    fun `ktor-utils`() = runKtorTests("utils")

    @Test
    fun `ktor-server-cio`() = runKtorTests("server", "cio")
}
