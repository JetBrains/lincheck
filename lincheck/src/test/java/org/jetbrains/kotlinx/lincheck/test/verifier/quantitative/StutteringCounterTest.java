package org.jetbrains.kotlinx.lincheck.test.verifier.quantitative;

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
import org.jetbrains.kotlinx.lincheck.Result;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.CostWithNextCostCounter;
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativeRelaxationVerifier;
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativeRelaxationVerifierConf;
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativeRelaxed;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.jetbrains.kotlinx.lincheck.verifier.quantitative.PathCostFunction.PHI_INTERVAL;

@Ignore
@StressCTest(verifier = QuantitativeRelaxationVerifier.class)
@QuantitativeRelaxationVerifierConf(factor = 3, pathCostFunc = PHI_INTERVAL,
    costCounter = StutteringCounterTest.CostCounter.class)
public class StutteringCounterTest {
    private StutteringCounterSimulation counter = new StutteringCounterSimulation(3, 0.9f);

    @QuantitativeRelaxed
    @Operation
    public int incAndGet() {
        return counter.incAndGet();
    }

    @Test
    public void test() {
        LinChecker.check(StutteringCounterTest.class);
    }

    // Predicate: counter is not incremented
    public static class CostCounter {
        private final int k;
        private final int value;

        public CostCounter(int k) {
            this(k, 0);
        }

        private CostCounter(int k, int value) {
            this.k = k;
            this.value = value;
        }

        public List<CostWithNextCostCounter<CostCounter>> incAndGet(Result result) {
            if (result.getValue().equals(value)) {
                // The counter is not incremented
                return Collections.singletonList(
                    new CostWithNextCostCounter<>(new CostCounter(k, value), true));
            } else if (result.getValue().equals(value + 1)) {
                // The counter is incremented
                return Collections.singletonList(
                    new CostWithNextCostCounter<>(new CostCounter(k, value + 1), false));
            } else {
                // Only incremented or the same values are possible
                return Collections.emptyList();
            }
        }
    }
}
