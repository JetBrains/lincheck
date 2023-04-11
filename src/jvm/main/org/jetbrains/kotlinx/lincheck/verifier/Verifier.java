package org.jetbrains.kotlinx.lincheck.verifier;

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

import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier;

/**
 * Implementation of this interface verifies that execution is correct with respect to the algorithm contract.
 * By default, it checks for linearizability (see {@link LinearizabilityVerifier}).
 * <p>
 * IMPORTANT!
 * All implementations should have {@code (Class<?> sequentialSpecification)} constructor,
 * which takes the scenario to be tested and the correct sequential implementation of the testing data structure.
 */
public interface Verifier {
    /**
     * Verifies the specified results for correctness.
     * Returns {@code true} if results are possible, {@code false} otherwise.
     */
    boolean verifyResults(ExecutionScenario scenario, ExecutionResult results);
}
