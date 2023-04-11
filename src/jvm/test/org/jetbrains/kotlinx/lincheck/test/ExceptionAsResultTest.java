package org.jetbrains.kotlinx.lincheck.test;

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.Assert.*;

@StressCTest(iterations = 1, invocationsPerIteration = 1)
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

