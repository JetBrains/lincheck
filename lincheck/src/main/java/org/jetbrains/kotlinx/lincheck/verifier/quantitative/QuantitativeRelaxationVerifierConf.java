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

import org.jetbrains.kotlinx.lincheck.*;

import java.lang.annotation.*;

/**
 * Configuration for {@link QuantitativelyRelaxedLinearizabilityVerifier}
 * which should be added to a test class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface QuantitativeRelaxationVerifierConf {
    /**
     * Relaxation factor
     */
    int factor();

    /**
     * Path cost function
     */
    PathCostFunction pathCostFunc();

    /**
     * Cost counter class.
     * <p>
     * This class represents a current data structure state
     * and has the same methods as testing operations,
     * but with an additional {@link Result} parameter
     * and another return type.
     * <p>
     * If an operation is not relaxed this cost counter
     * should check that the operation result is correct
     * and return the next state (which is a cost counter too)
     * or {@code null} in case the result is incorrect.
     * <p>
     * Otherwise, if a corresponding operation is relaxed
     * (annotated with {@link QuantitativeRelaxed}),
     * the method should return a list of all possible next states
     * with their transition cost. For this purpose,
     * a special {@link CostWithNextCostCounter} class should be used.
     * This class contains the next state and the transition cost
     * with the predicate value, which are defined in accordance
     * with the original paper. Thus, {@code List<CostWithNextCostCounter>}
     * should be returned by these methods and an empty list should
     * be returned in case no transitions are possible.
     * In order to restrict the number of possible transitions,
     * the relaxation factor should be used. It is provided via
     * a constructor, so {@code Lin-Check} uses the
     * {@code (int relaxationFactor)} constructor for the first
     * instance creation.
     */
    Class<?> costCounter();
}
