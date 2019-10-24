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

import org.jetbrains.kotlinx.lincheck.Result;

import java.util.List;
import java.util.Objects;

/**
 * This class represents a result corresponding to
 * the specified {@link ExecutionScenario scenario} execution.
 * <p>
 * All the result parts should have the same dimensions as the scenario.
 */
public class ExecutionResult implements ExecutionOutcome {
    /**
     * Results of the initial sequential part of the execution.
     * @see ExecutionScenario#initExecution
     */
    public final List<Result> initResults;
    /**
     * Results of the parallel part of the execution.
     * @see ExecutionScenario#parallelExecution
     */
    public final List<List<Result>> parallelResults;
    /**
     * Results of the last sequential part of the execution.
     * @see ExecutionScenario#postExecution
     */
    public final List<Result> postResults;

    public ExecutionResult(List<Result> initResults, List<List<Result>> parallelResults, List<Result> postResults) {
        this.initResults = initResults;
        this.parallelResults = parallelResults;
        this.postResults = postResults;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ExecutionResult that = (ExecutionResult) o;
        return Objects.equals(initResults, that.initResults) &&
            Objects.equals(parallelResults, that.parallelResults) &&
            Objects.equals(postResults, that.postResults);
    }

    @Override
    public int hashCode() {
        return Objects.hash(initResults, parallelResults, postResults);
    }
}