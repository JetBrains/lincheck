package com.devexperts.dxlab.lincheck.execution;

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

import com.devexperts.dxlab.lincheck.Actor;
import com.devexperts.dxlab.lincheck.CTestConfiguration;
import com.devexperts.dxlab.lincheck.CTestStructure;

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
    public ExecutionScenario nextExecution() {
        // Create init execution part
        List<ActorGenerator> notUseOnce = testStructure.actorGenerators.stream()
            .filter(ag -> !ag.useOnce()).collect(Collectors.toList());
        List<Actor> initExecution = new ArrayList<>();
        for (int i = 0; i < testConfiguration.actorsBefore && !notUseOnce.isEmpty(); i++) {
            ActorGenerator ag = notUseOnce.get(random.nextInt(notUseOnce.size()));
            initExecution.add(ag.generate());
        }
        // Create parallel execution part
        // Construct non-parallel groups and parallel one
        List<CTestStructure.OperationGroup> nonParallelGroups = testStructure.operationGroups.stream()
            .filter(g -> g.nonParallel)
            .collect(Collectors.toList());
        Collections.shuffle(nonParallelGroups);
        List<ActorGenerator> parallelGroup = new ArrayList<>(testStructure.actorGenerators);
        nonParallelGroups.forEach(g -> parallelGroup.removeAll(g.actors));

        List<List<Actor>> parallelExecution = new ArrayList<>();
        List<ThreadGen> threadGens = new ArrayList<>();
        for (int i = 0; i < testConfiguration.threads; i++) {
            parallelExecution.add(new ArrayList<>());
            threadGens.add(new ThreadGen(i, testConfiguration.actorsPerThread));
        }
        for (int i = 0; i < nonParallelGroups.size(); i++) {
            threadGens.get(i % threadGens.size()).nonParallelActorGenerators
                .addAll(nonParallelGroups.get(i).actors);
        }
        List<ThreadGen> tgs2 = new ArrayList<>(threadGens);
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
                parallelExecution.get(threadGen.threadNumber).add(agen.generate());
                if (--threadGen.left == 0)
                    it.remove();
            }
        }
        // Create post execution part
        List<ActorGenerator> leftActorGenerators = new ArrayList<>(parallelGroup);
        for (ThreadGen threadGen : tgs2)
            leftActorGenerators.addAll(threadGen.nonParallelActorGenerators);
        List<Actor> postExecution = new ArrayList<>();
        for (int i = 0; i < testConfiguration.actorsAfter && !leftActorGenerators.isEmpty(); i++) {
            ActorGenerator agen = getActorGenFromGroup(leftActorGenerators, random.nextInt(leftActorGenerators.size()));
            postExecution.add(agen.generate());
        }
        return new ExecutionScenario(initExecution, parallelExecution, postExecution);
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