/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.datastructures.verifier;

import org.jetbrains.kotlinx.lincheck.execution.*;

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
