/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.representation

// Regression for https://github.com/JetBrains/lincheck/issues/1048.
class ReceiverEqualsRepresentationTest : BaseTraceRepresentationTest("receiver_equals") {
    private val receiver = ReceiverWithThrowingEquals()

    override fun operation() {
        check(receiver.value() == 42)
    }
}

private class ReceiverWithThrowingEquals {
    fun value(): Int = 42

    override fun equals(other: Any?): Boolean = error("Receiver.equals must not be called")

    override fun hashCode(): Int = 0
}
