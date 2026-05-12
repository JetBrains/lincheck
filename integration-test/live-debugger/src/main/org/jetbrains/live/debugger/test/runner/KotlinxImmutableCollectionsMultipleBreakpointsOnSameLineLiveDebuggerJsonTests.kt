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

/**
 * Live-debugger integration suite for the "multiple breakpoints on the same line" behavior.
 *
 * Each JSON entry pins UUIDs on its breakpoints (so the trace's `Live breakpoint [<uuid>]`
 * lines compare deterministically against the goldens) and may attach a constant-boolean
 * `condition` per breakpoint to control whether it fires.
 *
 * The kotlinx-immutable test methods used as drivers are picked because each of them
 * deterministically executes the chosen breakpoint line a small, fixed number of times.
 */
abstract class KotlinxImmutableCollectionsMultipleBreakpointsOnSameLineLiveDebuggerJsonTests :
    AbstractGradleLiveDebuggerIntegrationTest() {

    override val projectPath = Paths.get("build", "integrationTestProjects", "kotlinx.collections.immutable").toString()

    // Distinct golden folder so this suite's `.txt` files don't collide with the existing
    // `KotlinxImmutableCollectionsLiveDebuggerJsonTests` goldens — both drive the same
    // `tests.contract.list.ImmutableListTest` methods on the same `projectPath`.
    override val goldenDataFolderName: String = "kotlinx.collections.immutable.multipleBreakpointsOnSameLine"

    companion object Companion : TestGenerator(
        groupName = "KotlinxImmutableCollectionsMultipleBreakpointsOnSameLineLiveDebugger",
        resourcePath = "/integrationTestData/kotlinxImmutableCollectionsMultipleBreakpointsOnSameLineLiveDebuggerTests.json",
        abstractTestClass = "KotlinxImmutableCollectionsMultipleBreakpointsOnSameLineLiveDebuggerJsonTests",
        packageName = "org.jetbrains.live.debugger.test.impl.generated",
        classNameSuffix = "JsonIntegrationTests",
        customImports = listOf("\nimport org.jetbrains.live.debugger.test.runner.*"),
        generatorMainClass = "org.jetbrains.live.debugger.test.runner.GenerateTestsKt.main",
    )
}
