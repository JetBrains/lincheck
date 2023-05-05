/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.verifier;

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
        boolean newResult = previousResults.computeIfAbsent(scenario, s -> new HashSet<>()).add(results);
        if (!newResult) return true;
        return verifyResultsImpl(scenario, results);
    }

    public abstract boolean verifyResultsImpl(ExecutionScenario scenario, ExecutionResult results);
}
