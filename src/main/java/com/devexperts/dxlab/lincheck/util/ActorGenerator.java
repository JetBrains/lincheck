/*
 *  Lincheck - Linearizability checker
 *  Copyright (C) 2015 Devexperts LLC
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.devexperts.dxlab.lincheck.util;

import java.util.Arrays;

public class ActorGenerator {
    private int methodId;
    private String name;
    private Interval[] rangeArgs;
    private boolean actorIsMutable = true;


    public ActorGenerator(int methodId, String name, Interval... rangeArgs) {
        this.methodId = methodId;
        this.name = name;
        this.rangeArgs = rangeArgs;
    }

    public void setMutable(boolean actorIsMutable) {
        this.actorIsMutable = actorIsMutable;
    }

    public boolean isMutable() {
        return actorIsMutable;
    }

    public Actor generate(int indActor) {
        Integer[] args = new Integer[rangeArgs.length];
        for (int i = 0; i < rangeArgs.length; i++) {
            args[i] = MyRandom.fromInterval(rangeArgs[i]);
        }

        Actor act = new Actor(indActor, methodId, isMutable(), args);
        act.methodName = name;
        return act;
    }

    @Override
    public String toString() {
        return "ActorGenerator{" +
                "methodId=" + methodId +
                ", name='" + name + '\'' +
                ", rangeArgs=" + Arrays.toString(rangeArgs) +
                '}';
    }
}
