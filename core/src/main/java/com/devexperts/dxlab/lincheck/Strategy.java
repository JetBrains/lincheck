package com.devexperts.dxlab.lincheck;

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

import com.devexperts.dxlab.lincheck.stress.StressCTestConfiguration;
import com.devexperts.dxlab.lincheck.stress.StressStrategy;
import com.devexperts.dxlab.lincheck.verifier.Verifier;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Implementation of this class describes how to run the generated execution.
 * <p>
 * Note that strategy could run execution several times. For strategy creating
 * {@link #createStrategy} method is used. It is impossible to add new strategy
 * without any code change.
 */
public abstract class Strategy {
    protected final Object testInstance;
    protected final Method resetMethod;
    protected final Verifier verifier;
    protected final List<List<Actor>> actorsPerThread;

    protected Strategy(Object testInstance, Method resetMethod, List<List<Actor>> actorsPerThread, Verifier verifier) {
        this.testInstance = testInstance;
        this.resetMethod = resetMethod;
        this.actorsPerThread = actorsPerThread;
        this.verifier = verifier;
    }

    /**
     * Creates {@link Strategy} based on {@code testCfg} type.
     */
    public static Strategy createStrategy(CTestConfiguration testCfg, Object testInstance,
        Method resetMethod, List<List<Actor>> actorsPerThread, Verifier verifier)
    {
        if (testCfg instanceof CTestConfiguration) {
            return new StressStrategy(testInstance, resetMethod, actorsPerThread, verifier,
                (StressCTestConfiguration) testCfg);
        }
        throw new IllegalArgumentException("Unknown strategy configuration type: " + testCfg.getClass());
    }

    public abstract void run() throws Exception;
}
