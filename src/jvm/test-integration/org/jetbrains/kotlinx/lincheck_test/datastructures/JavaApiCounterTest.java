/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.datastructures;

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.Test;

public class JavaApiCounterTest {
    private final Counter counter = new Counter();

    @Operation
    public int inc() {
        return counter.inc();
    }

    @Operation
    public int get() {
        return counter.get();
    }

    @Test(expected = AssertionError.class)
    public void stressTest() {
        new StressOptions().check(this.getClass());
    }

    @Test(expected = AssertionError.class)
    public void modelCheckingTest() {
        new ModelCheckingOptions().check(this.getClass());
    }

}
