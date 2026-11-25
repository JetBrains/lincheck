/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.strategy.eventstructure

import org.junit.Test

/**
 * Checks that the experimental strategy can read the field of a Java `record` class.
 *
 * `sun.misc.Unsafe.objectFieldOffset` throws `UnsupportedOperationException` for a record's fields
 * (the JVM refuses to hand out their offsets, JEP 395); Apache Pulsar's `ConcurrentLongHashMap` keeps
 * its table in such a record, and reading a component of it used to crash the checker instead of
 * returning the stored value.
 */
class RecordFieldAccessTest {

    @Test
    fun testRecordComponentIsReadable() {
        litmusTest(assertSame(setOf(42L))) {
            RecordFieldAccessHolder(42L).value()
        }
    }
}
