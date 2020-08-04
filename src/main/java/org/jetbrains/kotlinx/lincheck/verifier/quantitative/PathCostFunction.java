package org.jetbrains.kotlinx.lincheck.verifier.quantitative;

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

/**
 * Defines strategy to count a path cost according to the
 * "Quantitative relaxation of concurrent data structures"
 * paper by Henzinger, T. A., Kirsch, C. M., Payer, H.,
 * Sezgin, A., & Sokolova, A. (paragraph 4.3)
 */
public enum PathCostFunction {
    /**
     * Non-relaxed cost strategy checks that the transition cost equals zero.
     */
    NON_RELAXED {
        IterativePathCostFunctionCounter nonRelaxedPathCostFunctionCounter = null;

        @Override
        public IterativePathCostFunctionCounter createIterativePathCostFunctionCounter(int relaxationFactor) {
            return NON_RELAXED_PATH_COST_FUNCTION_COUNTER_SINGLETON;
        }
    },
    /**
     * Maximal cost strategy checks that the maximal transition cost
     * is less than the relaxation factor, ignores predicates.
     * <p>
     * More formally, <tt>pcost = max{cost_i | 1 <= i <= n}</tt>,
     * where <tt>cost_i</tt> is the cost of the <tt>i</tt>-th transition.
     */
    MAX {
        @Override
        public IterativePathCostFunctionCounter createIterativePathCostFunctionCounter(int relaxationFactor) {
            return new MaxIterativePathCostFunctionCounter(relaxationFactor);
        }
    },
    /**
     * Phi-interval strategy checks that the maximal subtrace length where the predicate is satisfied
     * is less than the relaxation factor, ignores costs.
     * <p>
     * More formally, <tt>pcost = max{j - i + 1 | phi(i, j) and 1 <= i <= j <= n}</tt>,
     * where <tt>phi(i, j)</tt> means that predicate is satisfied on <tt>[i, j]</tt> subtrace.
     */
    PHI_INTERVAL {
        @Override
        public IterativePathCostFunctionCounter createIterativePathCostFunctionCounter(int relaxationFactor) {
            return new PhiIntervalPathCostFunction(relaxationFactor);
        }
    },
    /**
     * Phi-interval restricted maximal cost strategy combines both {@link #MAX maximal}
     * and {@link #PHI_INTERVAL phi-interval} ones.
     * <p>
     * <tt>pcost = max{l(i, j) | phi(i, j) and 1 <= i <= j <= n}</tt>,
     * where <tt>l(i, j) = max{cost_r + (r - i + 1) | i <= r <= j}</tt>.
     */
    PHI_INTERVAL_RESTRICTED_MAX {
        @Override
        public IterativePathCostFunctionCounter createIterativePathCostFunctionCounter(int relaxationFactor) {
            return new PhiIntervalRestrictedMaxPathCostFunction(relaxationFactor);
        }
    };

    public abstract IterativePathCostFunctionCounter createIterativePathCostFunctionCounter(int relaxationFactor);

    private static class NonRelaxedPathCostFunctionCounter implements IterativePathCostFunctionCounter {
        @Override
        public IterativePathCostFunctionCounter next(int cost, boolean predicate) {
            if (cost != 0) throw new IllegalArgumentException("All costs should be zero");
            return this;
        }
    }
    private static final NonRelaxedPathCostFunctionCounter NON_RELAXED_PATH_COST_FUNCTION_COUNTER_SINGLETON = new NonRelaxedPathCostFunctionCounter();

    private static class MaxIterativePathCostFunctionCounter implements IterativePathCostFunctionCounter {
        private final int relaxationFactor;

        MaxIterativePathCostFunctionCounter(int relaxationFactor) {
            this.relaxationFactor = relaxationFactor;
        }

        @Override
        public IterativePathCostFunctionCounter next(int cost, boolean predicate) {
            return cost < relaxationFactor ? this : null;
        }
    }

    private static class PhiIntervalPathCostFunction implements IterativePathCostFunctionCounter {
        private final int relaxationFactor;
        private final int predicateSatisfactionCount;
        private final PhiIntervalPathCostFunction[] cache;

        PhiIntervalPathCostFunction(int relaxationFactor) {
            this(relaxationFactor, 0, new PhiIntervalPathCostFunction[relaxationFactor]);
            this.cache[0] = this;
        }

        private PhiIntervalPathCostFunction(int relaxationFactor, int predicateSatisfactionCount, PhiIntervalPathCostFunction[] cache) {
            this.relaxationFactor = relaxationFactor;
            this.predicateSatisfactionCount = predicateSatisfactionCount;
            this.cache = cache;
        }

        @Override
        public IterativePathCostFunctionCounter next(int cost, boolean predicate) {
            int newPredicateSatisfactionCount = predicate ? predicateSatisfactionCount + 1 : 0;
            // Check that the transition is possible
            if (newPredicateSatisfactionCount >= relaxationFactor)
                return null;
            // Get cached function counter
            IterativePathCostFunctionCounter res = cache[newPredicateSatisfactionCount];
            if (res == null)
                res = cache[newPredicateSatisfactionCount] = new PhiIntervalPathCostFunction(relaxationFactor, newPredicateSatisfactionCount, cache);
            return res;
        }
    }

    private static class PhiIntervalRestrictedMaxPathCostFunction implements IterativePathCostFunctionCounter {
        final int relaxationFactor;
        final int predicateSatisfactionCount;
        private final PhiIntervalRestrictedMaxPathCostFunction[] cache;

        PhiIntervalRestrictedMaxPathCostFunction(int relaxationFactor) {
            this(relaxationFactor, 0, new PhiIntervalRestrictedMaxPathCostFunction[relaxationFactor]);
        }

        private PhiIntervalRestrictedMaxPathCostFunction(int relaxationFactor, int predicateSatisfactionCount, PhiIntervalRestrictedMaxPathCostFunction[] cache) {
            this.relaxationFactor = relaxationFactor;
            this.predicateSatisfactionCount = predicateSatisfactionCount;
            this.cache = cache;
        }

        @Override
        public PhiIntervalRestrictedMaxPathCostFunction next(int cost, boolean predicate) {
            // Check that the transition is possible
            if (predicateSatisfactionCount + cost >= relaxationFactor)
                return null;
            int newPredicateSatisfactionCount = predicate ? predicateSatisfactionCount + 1 : 0;
            // Get cached function counter
            PhiIntervalRestrictedMaxPathCostFunction res = cache[newPredicateSatisfactionCount];
            if (res == null)
                res = cache[newPredicateSatisfactionCount] = new PhiIntervalRestrictedMaxPathCostFunction(relaxationFactor, newPredicateSatisfactionCount, cache);
            return res;
        }
    }
}