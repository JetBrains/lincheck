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

import AbstractGradleLiveDebuggerIntegrationTest
import org.jetbrains.trace.recorder.test.runner.TestGenerator
import java.nio.file.Paths

abstract class KotlinxImmutableCollectionsLiveDebuggerJsonTests : AbstractGradleLiveDebuggerIntegrationTest() {
    override val projectPath = Paths.get("build", "integrationTestProjects", "kotlinx.collections.immutable").toString()

    companion object Companion : TestGenerator(
        groupName = "KotlinxImmutableCollectionsLiveDebugger",
        resourcePath = "/integrationTestData/kotlinxImmutableCollectionsLiveDebuggerTests.json",
        abstractTestClass = "KotlinxImmutableCollectionsLiveDebuggerJsonTests",
        packageName = "org.jetbrains.live.debugger.test.impl.generated",
        customImports = listOf("\nimport org.jetbrains.live.debugger.test.runner.*"),
        generatorMainClass = "org.jetbrains.live.debugger.test.runner.GenerateTestsKt.main",
    )
}
