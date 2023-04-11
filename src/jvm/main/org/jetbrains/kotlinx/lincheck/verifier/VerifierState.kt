/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.verifier

/**
 * Abstract class for lincheck tests that defines the test instance state,
 * that is part of the equivalency relation among [LTS] states.
 *
 * [VerifierState] lazily counts the test instance state, caches it, and uses in `equals` and `hashCode` methods.
 */
@Deprecated("Doesn't always improve performance of verification", level = DeprecationLevel.WARNING)
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

    override fun equals(other: Any?) = this.state == (other as VerifierState).state
    override fun hashCode() = this.state.hashCode()
}
