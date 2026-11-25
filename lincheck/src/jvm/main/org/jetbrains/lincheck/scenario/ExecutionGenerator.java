/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.scenario;

import org.jetbrains.lincheck.LincheckSettings;
import org.jetbrains.lincheck.datastructures.Options;
import org.jetbrains.lincheck.datastructures.OptionsKt;
import org.jetbrains.lincheck.datastructures.RandomProvider;

/**
 * Implementation of this interface generates execution scenarios.
 * By default, {@link RandomExecutionGenerator} is used.
 * <br/>
 *
 * Implementations must provide a public constructor accepting {@link LincheckSettings},
 * {@link ConcurrentTestStructure}, and {@link RandomProvider}.
 */
public abstract class ExecutionGenerator {
    protected final LincheckSettings settings;

    /** @deprecated Use {@link #settings}; available only with the legacy constructor. */
    @Deprecated
    protected final Options<?> options;
    protected final ConcurrentTestStructure testStructure;

    protected ExecutionGenerator(LincheckSettings settings, ConcurrentTestStructure testStructure) {
        this.settings = settings;
        this.options = null;
        this.testStructure = testStructure;
    }

    /** @deprecated Use the constructor accepting {@link LincheckSettings}. */
    @Deprecated
    protected ExecutionGenerator(Options<?> options, ConcurrentTestStructure testStructure) {
        this.settings = OptionsKt.toSettings(options);
        this.options = options;
        this.testStructure = testStructure;
    }

    /**
     * Generates an execution scenario according to the parameters provided by the test configuration
     * and the restrictions from the test structure.
     * <br>
     *
     * If the current test contains suspendable operations, the initial part of an execution
     * should not contain suspendable actors and the post part should be empty.
     */
    public abstract ExecutionScenario nextExecution();
}
