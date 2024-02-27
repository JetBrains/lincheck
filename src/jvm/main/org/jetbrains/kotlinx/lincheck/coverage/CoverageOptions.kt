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

import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import com.intellij.rt.coverage.instrumentation.CoverageRuntime
import com.intellij.rt.coverage.instrumentation.InstrumentationOptions
import com.intellij.rt.coverage.instrumentation.data.ProjectContext
import com.intellij.rt.coverage.verify.ProjectTargetProcessor
import com.intellij.rt.coverage.verify.Verifier.CollectedCoverage
import java.util.regex.Pattern

/**
 * Creates object with coverage options.
 *
 * @param excludePatterns patterns for classnames to exclude from coverage report (you can use regex syntax).
 * @param onShutdown callback with coverage results.
 */
class CoverageOptions(
    excludePatterns: List<String> = listOf(),
    private val onShutdown: ((ProjectData, CollectedCoverage) -> Unit)? = null,
) {
    companion object {
        val globalProjectData = ProjectData()
        val globalProjectContext = ProjectContext(
            InstrumentationOptions.Builder()
                .setBranchCoverage(true)
                .setExcludePatterns(
                    // added to exclude ManagedStrategyStateHolder
                    listOf<Pattern>(Pattern.compile("org\\.jetbrains\\.kotlinx\\.lincheck\\..*"))
                )
                .setIsCalculateHits(false) // only allow to insert `__$hits$__[index] = 1` instructions by coverage transformer
                .build()
        )
    }
    var coverageResult: CoverageResult? = null
    private var collectedCoverage: CollectedCoverage? = null
    private val excludes = excludePatterns.map(Pattern::compile)

    init {
        if (ProjectData.ourProjectData != globalProjectData) {
            CoverageRuntime.installRuntime(globalProjectData)
        }
        resetCoveredClasses()
    }

    fun collectCoverage() {
        globalProjectContext.applyHits(globalProjectData)
        val localProjectData = getCoveredClasses()
        globalProjectContext.finalizeCoverage(localProjectData)

        ProjectTargetProcessor().process(localProjectData) { _, coverage ->
            collectedCoverage = coverage
            coverageResult = CoverageResult(coverage)
        }
    }

    fun onShutdown() {
        onShutdown?.let { it(globalProjectData, collectedCoverage!!) }
    }

    /**
     * Marks all lines, jumps, and switches of previously observed classes as uncovered.
     */
    private fun resetCoveredClasses() {
        globalProjectData.classesCollection.forEach { classData ->
            classData?.lines?.forEach inner@{
                if (it == null) return@inner

                val lineData = (it as LineData)
                lineData.hits = 0

                lineData.jumps?.forEach { jumpData ->
                    jumpData.trueHits = 0
                    jumpData.falseHits = 0
                }

                lineData.switches?.forEach { switchData ->
                    switchData.hits.fill(0)
                }
            }
        }
    }

    /**
     * Returns ProjectData, containing only "interesting" classes stored in globalProjectData.
     *
     * "Interesting" implies that class is not excluded, and it has at least a single line visited during last execution.
     */
    private fun getCoveredClasses() : ProjectData {
        val projectData = ProjectData()

        globalProjectData.classesCollection.forEach { classData ->
            if (
                classData != null &&
                excludes.none { it.matcher(classData.name).matches() } && // class is not excluded
                classData.lines.any { it != null && (it as LineData).hits != 0 } // class has at least single line covered
            ) {
                // we must make full copy of ClassData object, because finalizeCoverage method changes internals of each ClassData object, which is not valid to perform on global ProjectData
                val copy = projectData.getOrCreateClassData(classData.name)
                copy.merge(classData)
            }
        }

        return projectData
    }
}