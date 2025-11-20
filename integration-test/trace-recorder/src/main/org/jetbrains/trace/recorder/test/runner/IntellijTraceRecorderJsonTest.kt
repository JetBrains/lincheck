/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.runner

import java.nio.file.Paths

abstract class IntellijTraceRecorderJsonTest : AbstractJsonTraceRecorderIntegrationTest(
    projectPath = Paths.get("build", "integrationTestProjects", "intellij-community").toAbsolutePath().toString(),
    checkRepresentationByDefault = false,
) {
    companion object Companion : TestGenerator(
        groupName = "IJ",
        resourcePath = "/integrationTestData/intellijTests.json",
        abstractTestClass = "IntellijTraceRecorderJsonTest",
        packageName = "org.jetbrains.trace.recorder.test.impl.generated",
    )
}
