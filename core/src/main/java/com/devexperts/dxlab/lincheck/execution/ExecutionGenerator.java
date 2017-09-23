package com.devexperts.dxlab.lincheck.execution;

/*
 * #%L
 * core
 * %%
 * Copyright (C) 2015 - 2017 Devexperts, LLC
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

import com.devexperts.dxlab.lincheck.Actor;
import com.devexperts.dxlab.lincheck.CTestConfiguration;
import com.devexperts.dxlab.lincheck.CTestStructure;

import java.util.List;

/**
 * Implementation of this interface generates execution scenarios.
 * By default, {@link RandomExecutionGenerator} is used.
 * <p>
 * IMPORTANT!
 * All implementations should have the same constructor as {@link ExecutionGenerator} has.
 */
public abstract class ExecutionGenerator {
    public static final Class<? extends ExecutionGenerator> DEFAULT_IMPLEMENTATION = RandomExecutionGenerator.class;

    protected final CTestConfiguration testConfiguration;
    protected final CTestStructure testStructure;

    protected ExecutionGenerator(CTestConfiguration testConfiguration, CTestStructure testStructure) {
        this.testConfiguration = testConfiguration;
        this.testStructure = testStructure;
    }

    public abstract List<List<Actor>> nextExecution();
}
