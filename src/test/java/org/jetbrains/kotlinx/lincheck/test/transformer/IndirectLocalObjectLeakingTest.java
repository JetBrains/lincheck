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
package org.jetbrains.kotlinx.lincheck.test.transformer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest;
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static org.junit.Assert.fail;

/**
 * This test checks that indirect writes via Unsafe still lead to local object leaking.
 */
@ModelCheckingCTest(actorsPerThread = 1, actorsBefore = 0, actorsAfter = 0, iterations = 1)
public class IndirectLocalObjectLeakingTest extends VerifierState {
    private volatile VariableHolder holder = null;

    @Operation
    public int operation() {
        VariableHolder h = new VariableHolder();
        AtomicReferenceFieldUpdater afu = AtomicReferenceFieldUpdater.newUpdater(this.getClass(), VariableHolder.class, "holder");
        afu.compareAndSet(this, null, h); // initialize holder via CAS
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

    private class VariableHolder {
        int variable = 0;
    }
}
