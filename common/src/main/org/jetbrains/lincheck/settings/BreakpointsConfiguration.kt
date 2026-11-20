/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.settings

import org.jetbrains.lincheck.settings.privacy.BlockMatch
import org.jetbrains.lincheck.settings.privacy.BlockedFrame
import org.jetbrains.lincheck.settings.privacy.BlocklistMatcher
import org.jetbrains.lincheck.settings.privacy.RedactionMatch
import org.jetbrains.lincheck.settings.privacy.RedactionPolicy

/**
 * Snapshot instrumentation and capture inputs supplied by the owning agent.
 */
interface BreakpointsConfiguration {
    val lineBreakpoints: Map<BreakpointId, SnapshotBreakpoint>
    val blocklistEngine: BlocklistMatcher
    val redactionPolicy: RedactionPolicy
    val requiredRedactionPolicyValid: Boolean

    fun firstBlockedFrame(stackTrace: List<StackTraceElement>): BlockedFrame?
}

@Volatile
var breakpointsConfiguration: BreakpointsConfiguration = EmptyBreakpointsConfiguration

/**
 * No snapshot hooks are active unless an agent installs its configuration.
 */
private object EmptyBreakpointsConfiguration : BreakpointsConfiguration {
    override val lineBreakpoints: Map<BreakpointId, SnapshotBreakpoint> = emptyMap()

    override val blocklistEngine = object : BlocklistMatcher {
        override fun isEmpty() = true
        override fun classBlock(canonicalClassName: String): BlockMatch? = null
        override fun methodBlock(canonicalClassName: String, methodName: String, descriptor: String?): BlockMatch? = null
    }

    override val redactionPolicy = object : RedactionPolicy {
        override val hasNameRules = false
        override val isEmpty = true

        override fun matchName(variableName: String, declaringClassName: String?): RedactionMatch? = null
        override fun matchValue(boundedValue: String): RedactionMatch? = null
    }

    override val requiredRedactionPolicyValid = false

    override fun firstBlockedFrame(stackTrace: List<StackTraceElement>): BlockedFrame? = null
}
