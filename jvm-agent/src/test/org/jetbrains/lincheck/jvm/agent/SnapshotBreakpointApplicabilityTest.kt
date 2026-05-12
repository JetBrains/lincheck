/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.settings.applicableTo
import org.jetbrains.lincheck.settings.isApplicableTo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class SnapshotBreakpointApplicabilityTest {

    private fun bp(className: String, fileName: String, line: Int) =
        SnapshotBreakpoint(
            uuid = UUID.randomUUID(),
            className = className,
            fileName = fileName,
            lineNumber = line,
            conditionClassName = null,
            conditionFactoryMethodName = null,
            conditionCapturedVars = null,
            conditionCodeFragment = null,
        )

    @Test
    fun breakpointAppliesToOwnerClassAndGeneratedDescendants() {
        val outerBp = bp("com.example.IssuesResource", "IssuesResource.kt", 172)

        assertApplicableTo(outerBp, "com.example.IssuesResource", "IssuesResource.kt")
        assertApplicableTo(outerBp, "com.example.IssuesResource\$getIssue\$1", "IssuesResource.kt")
        assertApplicableTo(outerBp, "com.example.IssuesResource\$getIssue\$2", "IssuesResource.kt")
    }

    @Test
    fun breakpointDoesNotLeakToOtherFilesOrClasses() {
        val bp = bp("com.example.Foo", "Foo.kt", 10)

        assertNotApplicableTo(bp, "com.example.Foo\$method\$1", "OtherFile.kt")
        assertNotApplicableTo(bp, "com.example.Bar", "Foo.kt")
    }

    @Test
    fun classOnlyOverloadIsLooserThanFileAwareOverload() {
        val bp = bp("com.example.Foo", "Foo.kt", 10)

        // The class-only overload is the coarse pre-filter used in `shouldTransform`,
        // where the source file is not yet known. It accepts a `<owner>$...` descendant
        // even when the file does not match — the file-aware overload then rejects it.
        assertEquals(true, bp.isApplicableTo("com.example.Foo\$method\$1"))
        assertEquals(false, bp.isApplicableTo("com.example.Foo\$method\$1", "OtherFile.kt"))
    }

    private fun assertApplicableTo(
        breakpoint: SnapshotBreakpoint,
        canonicalClassName: String,
        sourceFileName: String,
    ) {
        assertEquals(
            listOf(breakpoint),
            listOf(breakpoint).applicableTo(canonicalClassName, sourceFileName),
        )
    }

    private fun assertNotApplicableTo(
        breakpoint: SnapshotBreakpoint,
        canonicalClassName: String,
        sourceFileName: String,
    ) {
        assertEquals(
            emptyList<SnapshotBreakpoint>(),
            listOf(breakpoint).applicableTo(canonicalClassName, sourceFileName),
        )
    }
}
