/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.analysis

import org.objectweb.asm.commons.AnalyzerAdapter

/**
 * ASM's [AnalyzerAdapter], which computes the JVM bytecode types
 * of values on the operand stack and in local variables.
 *
 * Aliased under a more descriptive name for clarity.
 */
typealias TypeAnalyzerAdapter = AnalyzerAdapter
