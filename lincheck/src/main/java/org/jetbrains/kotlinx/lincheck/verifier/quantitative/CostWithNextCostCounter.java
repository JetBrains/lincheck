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
 * This class is used to define possible transitions in {@link QuantitativeRelaxationVerifier}.
 *
 * @param <COST_COUNTER> cost counter class for the testing data structure.
 *                       It have to be defined for cleaner code.
 */
public class CostWithNextCostCounter<COST_COUNTER> {
    /**
     * Instance of the next cost counter instance.
     */
    COST_COUNTER nextCostCounter;

    /**
     * The transition cost.
     */
    int cost;

    /**
     * It is {@code true} if the transition predicate is satisfied.
     * By default {@code cost != 0} predicate is used.
     */
    boolean predicate;

    /**
     * Create new cost counter transition with the specified state and cost.
     * This constructor defines {@link #predicate transition predicate} at {@code cost != 0}.
     */
    public CostWithNextCostCounter(COST_COUNTER nextCostCounter, int cost) {
        this.nextCostCounter = nextCostCounter;
        this.cost = cost;
        this.predicate = cost != 0;
    }

    /**
     * Create new cost counter transition with the specified state and cost.
     * This constructor defines {@link #predicate transition predicate} at {@code cost != 0}.
     */
    public CostWithNextCostCounter(COST_COUNTER nextCostCounter, boolean predicate) {
        this.nextCostCounter = nextCostCounter;
        this.cost = Integer.MAX_VALUE;
        this.predicate = predicate;
    }

    /**
     * Create new cost counter transition with the specified state, cost and predicate satisfaction.
     */
    public CostWithNextCostCounter(COST_COUNTER nextCostCounter, int cost, boolean predicate) {
        this.nextCostCounter = nextCostCounter;
        this.cost = cost;
        this.predicate = predicate;
    }
}
