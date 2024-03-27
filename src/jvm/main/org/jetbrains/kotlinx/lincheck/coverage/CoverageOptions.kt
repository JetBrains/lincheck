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
import com.intellij.rt.coverage.util.CoverageReport
import com.intellij.rt.coverage.verify.ProjectTargetProcessor
import com.intellij.rt.coverage.verify.Verifier.CollectedCoverage
import java.io.File
import java.util.regex.Pattern

/**
 * Creates object with coverage options.
 *
 * @param excludePatterns patterns for classnames to exclude from coverage report (you can use regex syntax).
 * @param includePatterns patterns for classnames to include to coverage report (you can use regex syntax). This parameter is used to include into report classes from java standard library and other classes that are excluded from transformation by default. Makes sense to add this only to ModelChecking strategy.
 * @param dataFile file in which coverage report will be saved.
 * @param onShutdown callback with coverage results.
 */
class CoverageOptions(
    excludePatterns: List<String> = listOf(),
    val includePatterns: List<String> = listOf(),
    private val dataFile: File? = null,
    // TODO: this is now useless, since I am resetting global project data every fuzzing iteration,
    //  so after last iteration global project data is empty (so remove ProjectData from `onShutdown` callback)
    private val onShutdown: ((ProjectData, CoverageResult) -> Unit)? = null,
) {
    companion object {
        var coverageEnabled = false
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
    private val excludes = excludePatterns.map(Pattern::compile)

    init {
        coverageEnabled = true
        if (ProjectData.ourProjectData != globalProjectData) {
            CoverageRuntime.installRuntime(globalProjectData)
        }
        resetCoveredClasses()
    }

    /**
     * Calculates and stores last run coverage. Resets coverage runtime state for future runs.
     * Method must be called at the end of the test execution in order to get valid results.
     *
     * @return representation of covered classes for the latter run.
     */
    fun collectCoverage(): ProjectData {
        // coverageEnabled = false // TODO: delete or what?

        // get coverage for classes that we used during last execution
        globalProjectContext.applyHits(globalProjectData)
        val localProjectData = getCoveredClasses()
        globalProjectContext.finalizeCoverage(localProjectData)

        // save report to file if configured
        if (dataFile != null) {
            println("Save coverage report to ${dataFile.path}")
            CoverageReport.save(localProjectData, getProjectContextWithDataFile(globalProjectContext.options, dataFile))
        }

        // cache the coverage stats for last run
        ProjectTargetProcessor().process(localProjectData) { _, coverage ->
            coverageResult = CoverageResult(coverage)
        }

        // reset coverage runtime for future runs
        resetCoveredClasses()

        return localProjectData
    }

    fun onShutdown() {
        coverageEnabled = false
        onShutdown?.let { it(globalProjectData, coverageResult!!) }
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
                    switchData.defaultHits = 0
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

    private fun getProjectContextWithDataFile(from: InstrumentationOptions, dataFile: File): ProjectContext {
        val options = InstrumentationOptions.Builder()
            .setDataFile(dataFile) // set file where to save coverage report
            .setBranchCoverage(from.isBranchCoverage)
            .setIsMergeData(from.isMergeData)
            .setIsCalculateUnloaded(from.isCalculateUnloaded)
            .setInstructionCoverage(from.isInstructionCoverage)
            .setIsCalculateHits(from.isCalculateHits)
            .setSaveSource(from.isSaveSource)
            .setIncludePatterns(from.includePatterns)
            .setExcludePatterns(from.excludePatterns)
            .setIncludeAnnotations(from.includeAnnotations)
            .setExcludeAnnotations(from.excludeAnnotations)
            .setSourceMapFile(from.sourceMapFile)
            .setTestTrackingMode(from.testTrackingMode)
            .build()

        return ProjectContext(options)
    }
}