package org.jetbrains.kotlinx.lincheck.verifier;

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

import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario;
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This verifier checks that the specified results could be happen in serializable execution.
 * It just tries to find any operations sequence which execution produces the same results.
 */
public class SerializabilityVerifier extends CachedVerifier {
    private final LinearizabilityVerifier linearizabilityVerifier;

    public SerializabilityVerifier(ExecutionScenario scenario, Class<?> testClass) {
        this.linearizabilityVerifier = new LinearizabilityVerifier(convertScenario(scenario), testClass);
    }

    private static <T> List<List<T>> convert(List<T> initPart, List<List<T>> parallelPart, List<T> postPart) {
        List<T> allActors = new ArrayList<>(initPart);
        parallelPart.forEach(allActors::addAll);
        allActors.addAll(postPart);
        return allActors.stream().map(Collections::singletonList).collect(Collectors.toList());
    }

    private static ExecutionScenario convertScenario(ExecutionScenario scenario) {
        return new ExecutionScenario(
            Collections.emptyList(),
            convert(scenario.initExecution, scenario.parallelExecution, scenario.postExecution),
            Collections.emptyList()
        );
    }

    private static ExecutionResult convertResult(ExecutionResult scenario) {
        return new ExecutionResult(
            Collections.emptyList(),
            convert(scenario.initResults, scenario.parallelResults, scenario.postResults),
            Collections.emptyList()
        );
    }

    @Override
    public boolean verifyResultsImpl(ExecutionResult results) {
        return linearizabilityVerifier.verifyResultsImpl(convertResult(results));
    }
}
