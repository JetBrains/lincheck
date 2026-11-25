/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.strategy.eventstructure;

/**
 * A minimal Java {@code record}, used to test that the experimental strategy can access
 * the fields of a record class (`record` is a Kotlin reserved word, hence this file is Java).
 */
public record RecordFieldAccessHolder(long value) {
}
