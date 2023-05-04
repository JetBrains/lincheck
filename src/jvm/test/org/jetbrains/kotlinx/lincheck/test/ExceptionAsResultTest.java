/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.test;

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.Assert.*;

@StressCTest(iterations = 1, requireStateEquivalenceImplCheck = false)
public class ExceptionAsResultTest {
    private static final String MESSAGE = "iujdhfgilurtybfu";

    @Operation(handleExceptionsAsResult = NullPointerException.class)
    public void npeIsOk() {
        ((String) null).charAt(0);
    }

    @Operation(handleExceptionsAsResult = Exception.class)
    public void subclassExceptionIsOk() throws Exception {
        if (ThreadLocalRandom.current().nextBoolean()) throw new IOException(MESSAGE);
        else throw new IllegalStateException(MESSAGE);
    }

    @Test
    public void test() {
        try {
            LinChecker.check(ExceptionAsResultTest.class);
            fail("Should fail with AssertionError");
        } catch (AssertionError e) {
            String m = e.getMessage();
            assertTrue(m.contains("IllegalStateException") || m.contains("IOException"));
            assertFalse(m.contains(MESSAGE));
        }
    }
}

