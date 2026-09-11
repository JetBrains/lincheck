/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency

enum class MemoryModel {
    SequentialConsistency,
    ReleaseAcquire,
    JAM21,
}

fun String.toMemoryModel(): MemoryModel? = when (this) {
    "SC" -> MemoryModel.SequentialConsistency
    "SequentialConsistency" -> MemoryModel.SequentialConsistency
    "RA" -> MemoryModel.ReleaseAcquire
    "ReleaseAcquire" -> MemoryModel.SequentialConsistency
    "JAM" -> MemoryModel.JAM21
    "JAM21" -> MemoryModel.JAM21
    "Java" -> MemoryModel.JAM21
    else -> null
}
