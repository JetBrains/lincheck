/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.verifier

/**
 * Abstract class for lincheck tests that defines the test instance state,
 * that is part of the equivalency relation among [LTS] states.
 *
 * [VerifierState] lazily counts the test instance state, caches it, and uses in `equals` and `hashCode` methods.
 */
@Deprecated("Doesn't always improve performance of verification", level = DeprecationLevel.ERROR)
abstract class VerifierState {
    private var _state: Any? = null
    private val state: Any
        get() {
            if (_state === null) {
                _state = extractState()
            }
            return _state!!
        }

    /**
     * Note that this method is called *at once* and
     * it is fine if the whole data structure is broken
     * after its invocation.
     */
    protected abstract fun extractState(): Any
}
