/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.test_utils

fun loopIterationStart(loopId: Int) {
    beforeLoopIterationStarts.invoke(null, loopId)
}

fun loopEnd(loopId: Int) {
    afterLoopFinished.invoke(null, loopId)
}

private val INJECTIONS = Class.forName("sun.nio.ch.lincheck.Injections")
private val beforeLoopIterationStarts = INJECTIONS.methods
    .find {
        it.name == "beforeLoopIterationStarts"
    }!!
private val afterLoopFinished = INJECTIONS.methods
    .find { it.name == "afterLoopFinished" }!!