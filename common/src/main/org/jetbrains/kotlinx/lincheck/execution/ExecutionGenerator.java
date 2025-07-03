/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.execution;

import org.jetbrains.lincheck.datastructures.CTestConfiguration;
import org.jetbrains.kotlinx.lincheck.CTestStructure;

/**
 * Implementation of this interface generates execution scenarios.
 * By default, {@link RandomExecutionGenerator} is used.
 * <p>
 * IMPORTANT!
 * All implementations should have the same constructor as {@link ExecutionGenerator} has.
 */
public abstract class ExecutionGenerator {
    protected final CTestConfiguration testConfiguration;
    protected final CTestStructure testStructure;

    protected ExecutionGenerator(CTestConfiguration testConfiguration, CTestStructure testStructure) {
        this.testConfiguration = testConfiguration;
        this.testStructure = testStructure;
    }

    /**
     * Generates an execution scenario according to the parameters provided by the test configuration
     * and the restrictions from the test structure.
     *
     * If the current test contains suspendable operations, the initial part of an execution
     * should not contain suspendable actors and the post part should be empty.
     */
    public abstract ExecutionScenario nextExecution();
}
