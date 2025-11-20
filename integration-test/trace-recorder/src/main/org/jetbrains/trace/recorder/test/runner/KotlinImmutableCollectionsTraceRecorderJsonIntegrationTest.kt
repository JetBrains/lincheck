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

abstract class KotlinImmutableCollectionsTraceRecorderJsonTests : AbstractJsonTraceRecorderIntegrationTest(
    projectPath = Paths.get("build", "integrationTestProjects", "kotlinx.collections.immutable").toString(),
    checkRepresentationByDefault = true,
) {
    override val formatArgs: Map<String, String> = mapOf("format" to "text", "formatOption" to "verbose",)

    companion object Companion : TestGenerator(
        groupName = "KotlinImmutableCollections",
        resourcePath = "/integrationTestData/kotlinxImmutableCollectionsTests.json",
        abstractTestClass = "KotlinImmutableCollectionsTraceRecorderJsonTests",
        packageName = "org.jetbrains.trace.recorder.test.impl.generated",
        timeoutMinutes = 10,
    )
}
