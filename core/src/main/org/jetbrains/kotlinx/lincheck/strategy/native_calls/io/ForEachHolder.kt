/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.native_calls.io

import org.jetbrains.kotlinx.lincheck.strategy.native_calls.ReplayableMutableInstance
import java.util.ArrayList

/**
 * This class is used to track elements that are found during the last call of forEach(Remaining) methods.
 * @param T type of elements
 */
internal abstract class ForEachHolder<T> : ReplayableMutableInstance() {
    private val remainingElementsImpl = ArrayList<T>()
    val remainingElements: List<T> get() = remainingElementsImpl
    
    protected fun addRemainingElement(element: T) {
        verifyIsNotReplaying()
        remainingElementsImpl.add(element)
    }
    
    protected fun clearRemainingElements() {
        verifyIsNotReplaying()
        remainingElementsImpl.clear()
    }
}
