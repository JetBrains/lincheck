/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.runner

import AbstractGradleLiveDebuggerIntegrationTest
import java.nio.file.Paths

abstract class KtorLiveDebuggerJsonTests : AbstractGradleLiveDebuggerIntegrationTest() {
    override val projectPath = Paths.get("build", "integrationTestProjects", "ktor").toString()

    companion object Companion : TestGenerator(
        groupName = "KtorLiveDebugger",
        resourcePath = "/integrationTestData/ktorLiveDebuggerTests.json",
        abstractTestClass = "KtorLiveDebuggerJsonTests",
        packageName = "org.jetbrains.trace.recorder.test.impl.generated",
    )
}
