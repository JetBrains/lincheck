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
import com.intellij.rt.coverage.util.ProjectDataLoader
import com.intellij.rt.coverage.util.classFinder.ClassFinder
import java.io.File
import java.io.IOException
import java.util.regex.Pattern

/**
 * Creates object with coverage options.
 *
 * @param branchCoverage flag to run line coverage or branch coverage otherwise.
 * @param dataFile data file to save coverage result.
 * @param onShutdown callback with coverage results.
 */
class CoverageOptions(
    private val branchCoverage: Boolean = false,
    private val dataFile: File? = null,
    private val onShutdown: ((ProjectData) -> Unit)? = null
) {
    val projectData = ProjectData(dataFile, branchCoverage, null)
    val cf = ClassFinder(listOf(), listOf())

    init {
        projectData.excludePatterns = listOf(
            Pattern.compile("org\\.jetbrains\\.kotlinx\\.lincheck\\..*") // added to exclude ManagedStrategyStateHolder
        )

        createDataFile()
        CoverageRuntime.installRuntime(projectData)
    }

    fun onShutdown() {
        if (dataFile != null) {
            println("Saving coverage report to '${dataFile.path}'")
            CoverageReport(dataFile, false, cf, false).save(projectData)
        }

        onShutdown?.let { it(projectData) }
    }

    private fun createDataFile() {
        if (dataFile != null && !dataFile.exists()) {
            val parentDir = dataFile.parentFile;
            if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();
            dataFile.createNewFile();
        }
    }
}