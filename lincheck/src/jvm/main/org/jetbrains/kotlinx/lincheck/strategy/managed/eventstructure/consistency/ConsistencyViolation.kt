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

abstract class SequentialConsistencyViolation : Inconsistency()

class CoherenceViolation : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Sequential consistency coherence violation detected"
    }
}
class SequentialConsistencyApproximationInconsistency : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Approximate sequential inconsistency detected"
    }
}

class ReadModifyWriteAtomicityViolation(/*val write1: Event, val write2: Event*/) : Inconsistency() {
    override fun toString(): String {
        return "Atomicity violation detected"
    }
}
class ReleaseAcquireInconsistency : Inconsistency() {
    override fun toString(): String {
        return "Release/Acquire inconsistency detected"
    }
}
