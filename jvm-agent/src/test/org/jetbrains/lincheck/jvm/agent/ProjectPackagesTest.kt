/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.util.computeProjectPackages
import org.junit.Assert
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

class ProjectPackagesTest {
    private val projectRoot = Paths.get("..").toAbsolutePath().normalize()
    private val actual = listOf(
        "sun.nio.ch.lincheck", "org.jetbrains.lincheck", "org.jetbrains.kotlinx.lincheck",
        "org.jetbrains.lincheck_test.gpmc", "org.jetbrains.lincheck_test.guide", "org.jetbrains.kotlinx.lincheck_test",
        "org.jetbrains.trace.recorder.test.impl", "org.jetbrains.trace.recorder.test.runner", "org.jetbrains.lincheck_test.datastructures"
    ).also {
        // No package in the result should be a subpackage of another
        Assert.assertTrue(
            "Result must not contain subpackages of already included packages",
            it.none { p ->
                it.any { other ->
                    other != p && p.startsWith("$other.")
                }
            }
        )

        // All packages follow identifier segments joined by dots
        Assert.assertTrue(
            "Every package must be a sequence of Java identifiers separated by dots",
            it.all { pkg -> isValidPackageName(pkg) }
        )
    }

    @Test
    fun computesAllProjectPackages() {
        val patterns = computeProjectPackages(projectRoot)
        Assert.assertEquals("Include patterns must contain discovered project packages", actual, patterns)
    }

    @Test
    fun computeAllProjectPackagesFilteredDirs() {
        val patterns = computeProjectPackages(projectRoot, listOf(Paths.get("./bootstrap")))
        Assert.assertEquals(
            "Include patterns must contain discovered project packages without packages from 'bootstrap' folder",
            actual.filterNot { it == "sun.nio.ch.lincheck" },
            patterns
        )
    }

    private fun isValidPackageName(name: String): Boolean {
        // Helper functions remain the same
        fun isIdStart(ch: Char) = ch == '_' || ch.isLetter()
        fun isIdPart(ch: Char) = ch == '_' || ch.isLetterOrDigit()

        if (name.isEmpty()) return true

        // Split package name by dots and check if each part is valid
        val parts = name.split('.')
        if (parts.isEmpty()) return false

        // Check each package name part
        for (part in parts) {
            if (part.isEmpty()) return false

            // First character must be a letter or underscore
            if (!isIdStart(part[0])) return false

            // Rest of characters must be letters, digits, or underscores
            for (i in 1 until part.length) {
                if (!isIdPart(part[i])) return false
            }
        }

        return true
    }
}