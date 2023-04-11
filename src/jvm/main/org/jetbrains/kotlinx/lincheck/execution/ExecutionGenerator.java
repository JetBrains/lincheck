package org.jetbrains.kotlinx.lincheck.execution;

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

import org.jetbrains.kotlinx.lincheck.CTestConfiguration;
import org.jetbrains.kotlinx.lincheck.CTestStructure;

/**
 * Implementation of this interface generates execution scenarios.
 * By default, {@link RandomExecutionGenerator} is used.
 * <p>
 * IMPORTANT!
 * All implementations should have the same constructor as {@link ExecutionGenerator} has.
 */
public abstract class ExecutionGenerator {
    protected final CTestStructure testStructure;

    protected ExecutionGenerator(CTestStructure testStructure) {
        this.testStructure = testStructure;
    }

    /**
     * Generates an execution scenario according to the parameters provided by the test configuration
     * and the restrictions from the test structure.
     *
     * If the current test contains suspendable operations, the initial part of an execution
     * should not contain suspendable actors and the post part should be empty.
     */
    public abstract ExecutionScenario nextExecution(
            int threads, int operationsPerThread,
            int operationsInInitPart, int operationsInPostPart
    );
}
