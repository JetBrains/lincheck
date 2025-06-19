/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.transformation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.lincheck.LincheckAssertionError;
import org.jetbrains.lincheck.datastructures.Operation;
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
    private static final AtomicReferenceFieldUpdater<WritesViaAtomicFieldUpdaterTest, VariableHolder> afu = AtomicReferenceFieldUpdater.newUpdater(WritesViaAtomicFieldUpdaterTest.class, VariableHolder.class, "holder");

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
