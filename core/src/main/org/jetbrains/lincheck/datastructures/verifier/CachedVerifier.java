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

import java.util.*;

/**
 * This verifier cached the already verified results in a hash table,
 * and look into this hash table at first. In case of many invocations
 * with the same scenario, this optimization improves the verification
 * phase significantly.
 */
public abstract class CachedVerifier implements Verifier {
    private final Map<ExecutionScenario, Set<ExecutionResult>> previousResults = new WeakHashMap<>();

    @Override
    public boolean verifyResults(ExecutionScenario scenario, ExecutionResult results) {
        Set<ExecutionResult> executionResults = previousResults.computeIfAbsent(scenario, s -> new HashSet<>());
        if (executionResults.contains(results)) return true;
        boolean isValid = verifyResultsImpl(scenario, results);
        // We store in previousResults only correct executions.
        // Otherwise, as we re-use this verifier when doing replay in the Plugin, we could find incorrect execution
        // in this cache and indicate that incorrect result is correct.
        if (isValid) {
            executionResults.add(results);
        }
        return isValid;
    }

    public abstract boolean verifyResultsImpl(ExecutionScenario scenario, ExecutionResult results);
}
