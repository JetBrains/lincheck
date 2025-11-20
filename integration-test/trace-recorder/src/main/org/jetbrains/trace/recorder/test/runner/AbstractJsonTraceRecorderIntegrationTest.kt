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

import AbstractGradleTraceIntegrationTest
import java.util.Locale

abstract class AbstractJsonTraceRecorderIntegrationTest(
    override val projectPath: String,
) : AbstractGradleTraceIntegrationTest() {
    override val formatArgs: Map<String, String> = mapOf("format" to "binary", "formatOption" to "stream")

    data class TestCase(
        val className: String,
        val methodName: String,
        val gradleCommand: String,
        val jvmArgs: List<String>,
        val checkRepresentation: Boolean,
        val reasonForMuting: String? = null,
    )
}

