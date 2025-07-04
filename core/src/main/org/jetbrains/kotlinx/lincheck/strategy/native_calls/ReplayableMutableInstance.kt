/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.native_calls

internal abstract class ReplayableMutableInstance {
    protected var isReplaying = false
        private set
    
    internal fun setToReplayMode() {
        isReplaying = true
    }

    protected fun verifyIsNotReplaying() {
        if (isReplaying) error("Replaying is not allowed here")
    }
}
