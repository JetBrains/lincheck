/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.util

import org.junit.Ignore
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode

/**
 * Annotation used to mark elements that should be ignored when running in trace debugger mode.
 *
 * When [isInTraceDebuggerMode] is true, the annotation is a `type alias` of [Ignore].
 * Otherwise, it is just a new annotation, doing nothing.
 */
annotation class IgnoreInTraceDebuggerMode
