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
import com.devexperts.dxlab.lincheck.ActorGenerator;
import com.devexperts.dxlab.lincheck.CTestConfiguration;
import com.devexperts.dxlab.lincheck.CTestStructure;
import com.devexperts.dxlab.lincheck.TestThreadConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class RandomExecutionGenerator extends ExecutionGenerator {
    private final Random random = new Random(0);

    public RandomExecutionGenerator(CTestConfiguration testConfiguration, CTestStructure testStructure) {
        super(testConfiguration, testStructure);
    }

    @Override
    public List<List<Actor>> nextExecution() {
        // Construct non-parallel groups and parallel one
        List<CTestStructure.OperationGroup> nonParallelGroups = testStructure.operationGroups.stream()
            .filter(g -> g.nonParallel)
            .collect(Collectors.toList());
        Collections.shuffle(nonParallelGroups);
        List<ActorGenerator> parallelGroup = new ArrayList<>(testStructure.actorGenerators);
        nonParallelGroups.forEach(g -> parallelGroup.removeAll(g.actors));

        List<List<Actor>> actorsPerThread = new ArrayList<>();
        List<ThreadGen> threadGens = new ArrayList<>();
        for (int i = 0; i < testConfiguration.threadConfigurations.size(); i++) {
            actorsPerThread.add(new ArrayList<>());
            TestThreadConfiguration threadCfg = testConfiguration.threadConfigurations.get(i);
            int actorsInThread = threadCfg.minActors + random.nextInt(threadCfg.maxActors - threadCfg.minActors + 1);
            threadGens.add(new ThreadGen(i, actorsInThread));
        }
        for (int i = 0; i < nonParallelGroups.size(); i++) {
            threadGens.get(i % threadGens.size()).nonParallelActorGenerators
                .addAll(nonParallelGroups.get(i).actors);
        }
        while (!threadGens.isEmpty()) {
            for (Iterator<ThreadGen> it = threadGens.iterator(); it.hasNext(); ) {
                ThreadGen threadGen = it.next();
                int aGenIndexBound = threadGen.nonParallelActorGenerators.size() + parallelGroup.size();
                if (aGenIndexBound == 0) {
                    it.remove();
                    continue;
                }
                int aGenIndex = random.nextInt(aGenIndexBound);
                ActorGenerator agen;
                if (aGenIndex < threadGen.nonParallelActorGenerators.size()) {
                    agen = getActorGenFromGroup(threadGen.nonParallelActorGenerators, aGenIndex);
                } else {
                    agen = getActorGenFromGroup(parallelGroup,
                        aGenIndex - threadGen.nonParallelActorGenerators.size());
                }
                actorsPerThread.get(threadGen.threadNumber).add(agen.generate());
                if (--threadGen.left == 0)
                    it.remove();
            }
        }
        return actorsPerThread;
    }

    private ActorGenerator getActorGenFromGroup(List<ActorGenerator> aGens, int index) {
        ActorGenerator aGen = aGens.get(index);
        if (aGen.useOnce())
            aGens.remove(index);
        return aGen;
    }

    private class ThreadGen {
        final List<ActorGenerator> nonParallelActorGenerators = new ArrayList<>();
        int threadNumber;
        int left;

        ThreadGen(int threadNumber, int nActors) {
            this.threadNumber = threadNumber;
            this.left = nActors;
        }
    }
}
