/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation

class ConstructorThisLeakRepresentationTest : BaseTraceRepresentationTest("constructor_this_leak") {
    override fun operation() {
        ConstructorDerived()
    }
}

private object ConstructorRecorder {
    var value: Any? = null

    fun remember(value: Any) {
        this.value = value
    }
}

private open class ConstructorBase {
    init {
        ConstructorRecorder.remember(this)
    }
}

private class ConstructorDerived : ConstructorBase() {
    init {
        ConstructorRecorder.remember(this)
    }
}
