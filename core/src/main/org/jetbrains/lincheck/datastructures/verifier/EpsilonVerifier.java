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
 * This verifier does nothing and could be used for performance benchmarking.
 */
public class EpsilonVerifier implements Verifier {

    public EpsilonVerifier(Class<?> sequentialSpecification) {}

    @Override
    public boolean verifyResults(ExecutionScenario scenario, ExecutionResult results) {
        return true; // Always correct results :)
    }
}
