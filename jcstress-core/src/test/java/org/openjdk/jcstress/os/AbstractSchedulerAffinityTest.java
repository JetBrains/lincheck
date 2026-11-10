/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jcstress.os;

import org.junit.Assert;
import org.openjdk.jcstress.os.topology.Topology;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

public class AbstractSchedulerAffinityTest {

    public void runGlobal(Scheduler s, int maxThreads) {
        Queue<CPUMap> takenMaps = new LinkedBlockingQueue<>();

        List<SchedulingClass> cases = new ArrayList<>();
        for (int a = 1; a <= 4; a++) {
            List<SchedulingClass> skel = s.globalAffinityFor(a, maxThreads);
            for (int c = 0; c < 1000; c++) {
                cases.addAll(skel);
            }
        }

        Collections.shuffle(cases, new Random(12345));

        for (SchedulingClass scl : cases) {
            CPUMap cpuMap = s.tryAcquire(scl);
            while (cpuMap == null) {
                CPUMap old = takenMaps.poll();
                Assert.assertNotNull("Cannot schedule on empty system", old);
                s.release(old);
                cpuMap = s.tryAcquire(scl);
            }

            takenMaps.offer(cpuMap);

            Assert.assertEquals(scl.numActors(), cpuMap.actorThreads().length);
            for (int c : cpuMap.actorThreads()) {
                Assert.assertEquals(-1, c);
            }
            Assert.assertNotEquals(0, cpuMap.systemThreads().length);
            Assert.assertNotEquals("", cpuMap.globalAffinityMap());
            Assert.assertNotEquals(0, cpuMap.allocatedThreads().length);
        }
    }

    public void runLocal(Topology topo, Scheduler s, int maxThreads) {
        Queue<CPUMap> takenMaps = new LinkedBlockingQueue<>();

        List<SchedulingClass> cases = new ArrayList<>();
        for (int a = 1; a <= 4; a++) {
            List<SchedulingClass> skel = s.localAffinityFor(a, maxThreads);
            for (int c = 0; c < 1000; c++) {
                cases.addAll(skel);
            }
        }

        Collections.shuffle(cases, new Random(12345));

        for (SchedulingClass scl : cases) {
            CPUMap cpuMap = s.tryAcquire(scl);
            while (cpuMap == null) {
                CPUMap old = takenMaps.poll();
                Assert.assertNotNull("Cannot schedule on empty system", old);
                s.release(old);
                cpuMap = s.tryAcquire(scl);
            }

            takenMaps.offer(cpuMap);
            int[] actorMap = cpuMap.actorThreads();

            for (int a1 = 0; a1 < scl.numActors(); a1++) {
                for (int a2 = 0; a2 < scl.numActors(); a2++) {
                    if (scl.nodes[a1] == scl.nodes[a2]) {
                        Assert.assertEquals("Should be scheduled at the same package",
                                topo.threadToNode(actorMap[a1]), topo.threadToNode(actorMap[a2]));
                    } else {
                        Assert.assertNotEquals("Should be scheduled at the different packages",
                                topo.threadToNode(actorMap[a1]), topo.threadToNode(actorMap[a2]));
                    }
                    if (scl.cores[a1] == scl.cores[a2]) {
                        Assert.assertEquals("Should be scheduled at the same core: " + scl,
                                topo.threadToCore(actorMap[a1]), topo.threadToCore(actorMap[a2]));
                    } else {
                        Assert.assertNotEquals("Should be scheduled at the different core: " + scl,
                                topo.threadToCore(actorMap[a1]), topo.threadToCore(actorMap[a2]));
                    }
                }
            }

            Assert.assertNotEquals("", cpuMap.globalAffinityMap());
        }
    }

    public void runNone(Topology topo, Scheduler s, int maxThreads) {
        Queue<CPUMap> takenMaps = new LinkedBlockingQueue<>();

        List<SchedulingClass> cases = new ArrayList<>();
        for (int a = 1; a <= 4; a++) {
            List<SchedulingClass> skel = s.noneAffinityFor(a, maxThreads);
            for (int c = 0; c < 1000; c++) {
                cases.addAll(skel);
            }
        }

        Collections.shuffle(cases, new Random(12345));

        for (SchedulingClass scl : cases) {
            CPUMap cpuMap = s.tryAcquire(scl);
            while (cpuMap == null) {
                CPUMap old = takenMaps.poll();
                Assert.assertNotNull("Cannot schedule on empty system: " + scl, old);
                s.release(old);
                cpuMap = s.tryAcquire(scl);
            }

            takenMaps.offer(cpuMap);
            Assert.assertEquals(scl.numActors(), cpuMap.actorThreads().length);
            for (int c : cpuMap.actorThreads()) {
                Assert.assertEquals(-1, c);
            }
            Assert.assertEquals(0, cpuMap.systemThreads().length);
            Assert.assertEquals("", cpuMap.globalAffinityMap());
            Assert.assertNotEquals(0, cpuMap.allocatedThreads().length);
        }
    }

}
