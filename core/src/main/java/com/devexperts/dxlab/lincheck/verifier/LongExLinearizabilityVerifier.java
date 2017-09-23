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
import com.devexperts.dxlab.lincheck.Utils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

public class LongExLinearizabilityVerifier extends Verifier {
    public LongExLinearizabilityVerifier(List<List<Actor>> actorsPerThread, Object testInstance, Method resetMethod) {
        super(actorsPerThread, testInstance, resetMethod);
    }

    @Override
    public void verifyResults(List<List<Result>> results) {
        Utils.invokeReset(resetMethod, testInstance);
        int nThreads = results.size();
        State initialState = new State(new int[nThreads], 0, testInstance);
        int[] finalStateDesc = new int[nThreads];
        for (int i = 0; i < nThreads; i++) {
            finalStateDesc[i] = results.get(i).size();
        }
        PriorityQueue<State> q = new PriorityQueue<>();
        q.offer(initialState);
        while (!q.isEmpty()) {
            State state = q.poll();
            if (Arrays.equals(state.stateDesc, finalStateDesc)) {
                return; // Equivalent sequential execution has found
            }
            for (int t = 0; t < nThreads; t++) {
                int nextOpId = state.stateDesc[t];
                if (nextOpId < results.get(t).size()) {
                    // execute next operation in i-th thread
                    Object newTestInstance = Utils.deepCopy(state.testInstance);
                    Actor actor = actorsPerThread.get(t).get(nextOpId);
                    Result result = executeActor(newTestInstance, actor);
                    if (results.get(t).get(nextOpId).equals(result)) {
                        int[] newStateDesc = Arrays.copyOf(state.stateDesc, state.stateDesc.length);
                        newStateDesc[t]++;
                        q.offer(new State(newStateDesc, state.executedActors + 1, newTestInstance));
                    }
                }
            }
        }
        System.out.println("Non-linearizable result:");
        results.forEach(System.out::println);
        throw new AssertionError("No linearizable execution found");
    }

    private static class State implements Comparable<State> {
        final int[] stateDesc;
        final int executedActors;
        final Object testInstance;

        State(int[] stateDesc, int executedActors, Object testInstanceState) {
            this.stateDesc = stateDesc;
            this.executedActors = executedActors;
            this.testInstance = testInstanceState;
        }

        @Override
        public int compareTo(State other) {
            // greater is more priority
            return Integer.compare(other.executedActors, executedActors);
        }

        @Override
        public String toString() {
            return "" + executedActors;
        }
    }
}
