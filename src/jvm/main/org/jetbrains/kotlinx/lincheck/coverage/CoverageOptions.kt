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
import com.intellij.rt.coverage.instrumentation.CoverageRuntime
import com.intellij.rt.coverage.util.CoverageReport
import com.intellij.rt.coverage.util.classFinder.ClassFinder
import java.util.regex.Pattern

/**
 * Creates object with coverage options.
 *
 * @param branchCoverage flag to run line coverage or branch coverage otherwise.
 * @param appendUnloaded flag to include classes that were unloaded during execution into coverage report.
 * @param onShutdown callback with coverage results.
 * @param additionalExcludePatterns patterns to exclude from coverage report.
 */
class CoverageOptions(
    private val branchCoverage: Boolean = false,
    private val appendUnloaded: Boolean = false,
    private val onShutdown: ((ProjectData) -> Unit)? = null,
    additionalExcludePatterns: List<Pattern> = listOf(),
) {
    private val excludePatterns = listOf<Pattern>(
        Pattern.compile("org\\.jetbrains\\.kotlinx\\.lincheck\\..*"), // added to exclude ManagedStrategyStateHolder
        // TODO: add other patterns to exclude (eg. gradle, junit, kotlinx, maven, ...)
    ) + additionalExcludePatterns
    val projectData = ProjectData(null, branchCoverage, null)
    val cf = ClassFinder(listOf(), excludePatterns)

    init {
        // only allow to insert `__$hits$__[index] = 1` instructions by coverage transformer
        com.intellij.rt.coverage.util.OptionsUtil.CALCULATE_HITS_COUNT = false

        projectData.excludePatterns = excludePatterns
        CoverageRuntime.installRuntime(projectData)
    }

    fun onShutdown() {
        CoverageReport.finalizeCoverage(projectData, appendUnloaded, cf, false);
        onShutdown?.let { it(projectData) }
    }
}