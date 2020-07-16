/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy.managed;

import org.jetbrains.kotlinx.lincheck.CTestConfiguration;
import org.jetbrains.kotlinx.lincheck.Options;

import java.util.ArrayList;
import java.util.List;

import static org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration.*;
import static org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration.DEFAULT_GUARANTEES;

/**
 * Options for managed strategies.
 */
public abstract class ManagedOptions<OPT extends Options, CTEST extends CTestConfiguration> extends Options<OPT, CTEST> {
    protected boolean checkObstructionFreedom = DEFAULT_CHECK_OBSTRUCTION_FREEDOM;
    protected int hangingDetectionThreshold = DEFAULT_HANGING_DETECTION_THRESHOLD;
    protected int invocationsPerIteration = DEFAULT_INVOCATIONS;
    protected final List<ManagedGuarantee> guarantees = new ArrayList<>(DEFAULT_GUARANTEES);

    /**
     * Check obstruction freedom of the concurrent algorithm.
     * In case of finding an obstruction lincheck will immediately stop and report it.
     */
    public OPT checkObstructionFreedom(boolean checkObstructionFreedom) {
        this.checkObstructionFreedom = checkObstructionFreedom;
        return (OPT) this;
    }

    /**
     * Use the specified maximum number of repetitions to detect endless loops.
     * A found loop will force managed execution to switch the executing thread.
     * In case of checkObstructionFreedom enabled it will report the obstruction instead.
     */
    public OPT hangingDetectionThreshold(int maxRepetitions) {
        this.hangingDetectionThreshold = maxRepetitions;
        return (OPT) this;
    }

    /**
     * The number of invocations that managed strategy may use to search for an incorrect execution.
     * In case of small scenarios with only a few "interesting" code locations a lesser than this
     * number of invocations will be used.
     */
    public OPT invocationsPerIteration(int invocationsPerIteration) {
        this.invocationsPerIteration = invocationsPerIteration;
        return (OPT) this;
    }

    /**
     * Add a guarantee that methods in some classes are either correct in terms of concurrent execution or irrelevant.
     * These guarantees can be used for optimization. For example, we can add a guarantee that all methods
     * in java.util.concurrent.ConcurrentHashMap are correct and then managed strategies will not try to switch threads
     * inside the methods. We can also mark methods in logging classes irrelevant if they do influence execution result.
     */
    public OPT addGuarantee(ManagedGuarantee guarantee) {
        this.guarantees.add(guarantee);
        return (OPT) this;
    }
}
