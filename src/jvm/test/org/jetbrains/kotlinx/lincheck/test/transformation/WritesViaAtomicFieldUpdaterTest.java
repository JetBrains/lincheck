/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.test.transformation;

import org.jetbrains.annotations.*;
import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*;
import org.jetbrains.kotlinx.lincheck.verifier.*;
import org.junit.*;

import java.util.concurrent.atomic.*;

import static org.junit.Assert.*;

/**
 * This test checks that indirect writes via {@link AtomicIntegerFieldUpdater}-s
 * are processed as normal writes.
 */
@ModelCheckingCTest(actorsPerThread = 1, actorsBefore = 0, actorsAfter = 0, iterations = 1)
public class WritesViaAtomicFieldUpdaterTest extends VerifierState {
    private static final AtomicReferenceFieldUpdater<WritesViaAtomicFieldUpdaterTest, VariableHolder> afu =
            AtomicReferenceFieldUpdater.newUpdater(WritesViaAtomicFieldUpdaterTest.class, VariableHolder.class, "holder");

    private volatile VariableHolder holder = null;

    @Operation
    public int operation() {
        VariableHolder h = new VariableHolder();
        afu.updateAndGet(this, cur -> h); // initialize holder via CAS
        return holder.variable++;
    }

    @Test
    public void test() {
        try {
            LinChecker.check(this.getClass());
        } catch (LincheckAssertionError e) {
            // the test should fail
            return;
        }
        fail("Written VariableHolder was wrongly treated as local object");
    }

    @NotNull
    @Override
    protected Object extractState() {
        if (holder == null) return -1;
        return holder.variable;
    }

    private static class VariableHolder {
        int variable = 0;
    }
}
