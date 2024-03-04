/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.coverage

import com.intellij.rt.coverage.data.ProjectData
import com.intellij.rt.coverage.instrumentation.CoverageTransformer
import com.intellij.rt.coverage.instrumentation.data.ProjectContext
import java.util.regex.Pattern

class LincheckCoverageTransformer(
    projectData: ProjectData,
    projectContext: ProjectContext,
    allowedJavaInternalPatterns: List<String> = emptyList(),
) : CoverageTransformer(projectData, projectContext) {
    private val allowedInternalClasses = allowedJavaInternalPatterns.map(Pattern::compile)

    override fun isInternalJavaClass(className: String?): Boolean {
        if (className != null && allowedInternalClasses.any { it.matcher(className).matches() }) {
            return false
        }

        return super.isInternalJavaClass(className)
    }
}