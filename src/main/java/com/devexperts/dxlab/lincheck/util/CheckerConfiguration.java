package com.devexperts.dxlab.lincheck.util;

/*
 * #%L
 * lin-check
 * %%
 * Copyright (C) 2015 - 2016 Devexperts, LLC
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

import java.util.ArrayList;
import java.util.List;

public class CheckerConfiguration {
    private int numThreads;

    private int numIterations;

    private List<Interval> rangeActorCount;
    private List<ActorGenerator> actorGenerators;
    private int indActor;

    public CheckerConfiguration(int numThreads, int numIterations, List<Interval> rangeActorCount, List<ActorGenerator> actorGenerators) {
        this.numThreads = numThreads;
        this.numIterations = numIterations;
        this.rangeActorCount = rangeActorCount;
        this.actorGenerators = actorGenerators;
    }

    public CheckerConfiguration() {
        numThreads = 0;
        numIterations = 0;
        rangeActorCount = new ArrayList<>();
        actorGenerators = new ArrayList<>();
    }

    public CheckerConfiguration setNumIterations(int n) {
        numIterations = n;
        return this;
    }

    public CheckerConfiguration addThread(Interval i) {
        numThreads++;
        rangeActorCount.add(i);
        return this;
    }

    public CheckerConfiguration addActorGenerator(ActorGenerator ag) {
        actorGenerators.add(ag);
        return this;
    }

    private ActorGenerator randomGenerator() {
        return actorGenerators.get(MyRandom.nextInt(actorGenerators.size()));
    }

    private Actor[] generateActorsArray(Interval count) {
        int countActors = MyRandom.fromInterval(count);

        Actor[] actors = new Actor[countActors];
        for (int i = 0; i < countActors; i++) {
            actors[i] = randomGenerator().generate(indActor++);
        }

        return actors;
    }

    public int getNumIterations() {
        return numIterations;
    }

    public int getNumThreads() {
        return numThreads;
    }

    public Actor[][] generateActors(boolean immutableFix) {
        indActor = 0;

        Actor[][] result = new Actor[numThreads][];

        int minCountRow = Integer.MAX_VALUE;
        for (int i = 0; i < numThreads; i++) {
            result[i] = generateActorsArray(rangeActorCount.get(i));
            minCountRow = Math.min(minCountRow, result[i].length);
        }

        if (immutableFix) {
            for (int row = 0; row < minCountRow; row++) {
                boolean allImmutable = true;
                for (int i = 0; i < numThreads; i++) {
                    if (result[i][row].isMutable) {
                        allImmutable = false;
                        break;
                    }
                }
                if (allImmutable) {
                    int ind = MyRandom.nextInt(numThreads);
                    while (!result[ind][row].isMutable) {
                        result[ind][row] = randomGenerator().generate(result[ind][row].ind);
                    }
                }
            }
        }

        return result;
    }

    @Override
    public String toString() {
        return "CheckerConfiguration{" +
                "numThreads=" + numThreads +
                ", numIterations=" + numIterations +
                ", rangeActorCount=" + rangeActorCount +
                ", actorGenerators=" + actorGenerators +
                ", indActor=" + indActor +
                '}';
    }
}
