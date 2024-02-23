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

import com.intellij.rt.coverage.verify.Verifier.CollectedCoverage


class CoverageResult(collected: CollectedCoverage) {
    val lineCoverage = collected.lineCounter.covered
    val totalLines = collected.lineCounter.missed + collected.lineCounter.covered

    val branchCoverage = collected.branchCounter.covered
    val totalBranches = collected.branchCounter.missed + collected.branchCounter.covered
}