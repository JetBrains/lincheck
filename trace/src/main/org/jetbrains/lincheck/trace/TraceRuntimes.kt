/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

/**
 * Identifies the JVM as the runtime that produced a trace,
 * in both places that identifier travels: the agent hello (`AgentHelloMessage.runtime`)
 * and the binary stream header.
 *
 * Every runtime names itself, and a reader passes an unrecognised name through,
 * so this build needs no constant for the runtimes it does not implement.
 */
const val RUNTIME_JVM: String = "jvm"
