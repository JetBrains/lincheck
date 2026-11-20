/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions

import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.ClassModel
import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.LocalVariableInfo
import org.jetbrains.lincheck.settings.BreakpointId
import org.jetbrains.lincheck.settings.SnapshotBreakpoint

/**
 * Resolves source expressions once instrumentation has the enclosing class and active local slots.
 */
internal fun interface BreakpointExpressionCompiler {
    /**
     * Returns bytecode-backed expressions, or null when compilation failed and was reported.
     */
    fun compile(
        breakpointId: BreakpointId,
        breakpoint: SnapshotBreakpoint,
        activeLocals: Collection<LocalVariableInfo>,
        enclosingClass: ClassModel,
        classLoader: ClassLoader?,
    ): SnapshotBreakpoint?
}

@Volatile
internal var breakpointExpressionCompiler: BreakpointExpressionCompiler =
    BreakpointExpressionCompiler { _, breakpoint, _, _, _ ->
        check(
            (breakpoint.conditionClasses != null || breakpoint.conditionSource.isNullOrBlank()) &&
                (breakpoint.watchClasses != null || breakpoint.watchSources.orEmpty().all { it.isBlank() })
        ) {
            "Source expression compiler has not been installed"
        }
        breakpoint
    }
