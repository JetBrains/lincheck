package com.devexperts.dxlab.lincheck.verifier;

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
import com.devexperts.dxlab.lincheck.Result;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This verifier checks for linearizability.
 */
public class LinearizabilityVerifier extends Verifier {
    private final Set<List<List<Result>>> possibleResultsSet;

    public LinearizabilityVerifier(List<List<Actor>> actorsPerThread, Object testInstance, Method resetMethod) {
        super(actorsPerThread, testInstance, resetMethod);
        // Generate all possible results
        possibleResultsSet = generateAllLinearizableExecutions(actorsPerThread).stream()
            .map(linEx -> {
                List<Result> res = executeActors(testInstance, linEx);
                Map<Actor, Result> resultMap = new IdentityHashMap<>();
                for (int i = 0; i < linEx.size(); i++) {
                    resultMap.put(linEx.get(i), res.get(i));
                }
                return actorsPerThread.stream()
                    .map(actors -> actors.stream()
                        .map(resultMap::get)
                        .collect(Collectors.toList())
                    ).collect(Collectors.toList());
            }).collect(Collectors.toSet());
    }

    @Override
    public void verifyResults(List<List<Result>> results) {
        // Throw an AssertionError if current execution
        // is not linearizable and log invalid execution
        if (!possibleResultsSet.contains(results)) {
            System.out.println("\nNon-linearizable execution:");
            results.forEach(System.out::println);
            System.out.println("\nPossible linearizable executions:");
            possibleResultsSet.forEach(possibleResults -> {
                possibleResults.forEach(System.out::println);
                System.out.println();
            });
            throw new AssertionError("Non-linearizable execution detected, see log for details");
        }
    }

    private List<List<Actor>> generateAllLinearizableExecutions(List<List<Actor>> actorsPerThread) {
        List<List<Actor>> executions = new ArrayList<>();
        generateLinearizableExecutions0(executions, actorsPerThread, new ArrayList<>(), new int[actorsPerThread.size()],
            actorsPerThread.stream().mapToInt(List::size).sum());
        return executions;
    }

    private void generateLinearizableExecutions0(List<List<Actor>> executions, List<List<Actor>> actorsPerThread,
        ArrayList<Actor> currentExecution, int[] indexes, int length)
    {
        if (currentExecution.size() == length) {
            executions.add((List<Actor>) currentExecution.clone());
            return;
        }
        for (int i = 0; i < indexes.length; i++) {
            List<Actor> actors = actorsPerThread.get(i);
            if (indexes[i] == actors.size())
                continue;
            currentExecution.add(actors.get(indexes[i]));
            indexes[i]++;
            generateLinearizableExecutions0(executions, actorsPerThread, currentExecution, indexes, length);
            indexes[i]--;
            currentExecution.remove(currentExecution.size() - 1);
        }
    }
}
