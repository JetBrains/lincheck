/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.execution;

import org.jetbrains.kotlinx.lincheck.Actor;
import org.jetbrains.lincheck.datastructures.CTestConfiguration;
import org.jetbrains.kotlinx.lincheck.CTestStructure;
import org.jetbrains.lincheck.datastructures.RandomProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class RandomExecutionGenerator extends ExecutionGenerator {
    private final Random random;

    public RandomExecutionGenerator(CTestConfiguration testConfiguration, CTestStructure testStructure, RandomProvider randomProvider) {
        super(testConfiguration, testStructure);
        random = randomProvider.createRandom();
    }

    @Override
    public ExecutionScenario nextExecution() {
        // Create init execution part
        List<ActorGenerator> validActorGeneratorsForInit = testStructure.actorGenerators.stream()
            .filter(ag -> !ag.getUseOnce() && !ag.isSuspendable()).collect(Collectors.toList());
        List<Actor> initExecution = new ArrayList<>();
        for (int i = 0; i < testConfiguration.getActorsBefore() && !validActorGeneratorsForInit.isEmpty(); i++) {
            ActorGenerator ag = validActorGeneratorsForInit.get(random.nextInt(validActorGeneratorsForInit.size()));
            initExecution.add(ag.generate(0, random));
        }
        // Create parallel execution part
        // Construct non-parallel groups and parallel one
        List<CTestStructure.OperationGroup> nonParallelGroups = testStructure.operationGroups.stream()
            .filter(g -> g.nonParallel)
            .collect(Collectors.toList());
        Collections.shuffle(nonParallelGroups, random);
        List<ActorGenerator> parallelGroup = new ArrayList<>(testStructure.actorGenerators);
        nonParallelGroups.forEach(g -> parallelGroup.removeAll(g.actors));

        List<List<Actor>> parallelExecution = new ArrayList<>();
        List<ThreadGen> threadGens = new ArrayList<>();
        for (int t = 0; t < testConfiguration.getThreads(); t++) {
            parallelExecution.add(new ArrayList<>());
            threadGens.add(new ThreadGen(t, testConfiguration.getActorsPerThread()));
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
                parallelExecution.get(threadGen.iThread).add(agen.generate(threadGen.iThread + 1, random));
                if (--threadGen.left == 0)
                    it.remove();
            }
        }
        parallelExecution = parallelExecution.stream().filter(actors -> !actors.isEmpty()).collect(Collectors.toList());
        // Create post execution part if the parallel part does not have suspendable actors
        List<Actor> postExecution;
        if (parallelExecution.stream().noneMatch(actors -> actors.stream().anyMatch(Actor::isSuspendable))) {
            postExecution = new ArrayList<>();
            List<ActorGenerator> leftActorGenerators = new ArrayList<>(parallelGroup);
            for (ThreadGen threadGen : tgs2)
                leftActorGenerators.addAll(threadGen.nonParallelActorGenerators);
            for (int i = 0; i < testConfiguration.getActorsAfter() && !leftActorGenerators.isEmpty(); i++) {
                ActorGenerator agen = getActorGenFromGroup(leftActorGenerators, random.nextInt(leftActorGenerators.size()));
                postExecution.add(agen.generate(testConfiguration.getThreads() + 1, random));
            }
        } else {
            postExecution = Collections.emptyList();
        }
        return new ExecutionScenario(initExecution, parallelExecution, postExecution, testStructure.validationFunction);
    }

    private ActorGenerator getActorGenFromGroup(List<ActorGenerator> aGens, int index) {
        ActorGenerator aGen = aGens.get(index);
        if (aGen.getUseOnce())
            aGens.remove(index);
        return aGen;
    }

    private static class ThreadGen {
        final List<ActorGenerator> nonParallelActorGenerators = new ArrayList<>();
        int iThread;
        int left;

        ThreadGen(int iThread, int nActors) {
            this.iThread = iThread;
            this.left = nActors;
        }
    }
}