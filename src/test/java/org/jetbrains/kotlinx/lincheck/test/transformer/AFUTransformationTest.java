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
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest;
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * This test checks the transformation of java.util package handles AFU correctly,
 * because AFU can not be transformed due to CallerSensitive annotation.
 * Specifically checks updateAndGet method that accepts java.util.function IntUnaryOperator
 * that, if transformed, leads to incompatible types.
 */
@ModelCheckingCTest(actorsAfter = 0, actorsBefore = 0, actorsPerThread = 1, iterations = 1)
public class AFUTransformationTest extends VerifierState {
    public volatile int a = 0;

    @Operation
    public int inc() {
        AtomicIntegerFieldUpdater fu = AtomicIntegerFieldUpdater.newUpdater(this.getClass(), "a");
        return fu.updateAndGet(this, x -> 2 * x + 3);
    }

    @Test
    public void test() {
        LinChecker.check(this.getClass());
    }

    @NotNull
    @Override
    protected Object extractState() {
        return a;
    }
}
