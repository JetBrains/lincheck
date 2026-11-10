/*
 * Copyright (c) 2021, Red Hat, Inc. All rights reserved.
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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.openjdk.jcstress.os.topology.PresetListTopology;
import org.openjdk.jcstress.os.topology.TopologyParseException;

import java.util.*;

@RunWith(Parameterized.class)
public class SchedulerAffinityIrregularTest extends AbstractSchedulerAffinityTest {

    @Parameterized.Parameters(name = "n={0}")
    public static Iterable<Object[]> data() {
        List<Object[]> r = new ArrayList<>();
        for (int n = 0; n < 2048; n++) {
            r.add(new Object[] { n });
        }
        return r;
    }

    @Parameterized.Parameter(0)
    public int n;

    @Test
    public void test_Local() throws TopologyParseException {
        PresetListTopology topo = generate();

        int maxThreads = topo.totalThreads();
        Scheduler s = new Scheduler(topo, maxThreads);
        s.enableDebug();

        runLocal(topo, s, maxThreads);
    }

    @Test
    public void test_Global() throws TopologyParseException {
        PresetListTopology topo = generate();

        int maxThreads = topo.totalThreads();
        Scheduler s = new Scheduler(topo, maxThreads);
        s.enableDebug();

        runGlobal(s, maxThreads);
    }

    @Test
    public void test_None() throws TopologyParseException {
        PresetListTopology topo = generate();

        int maxThreads = topo.totalThreads();
        Scheduler s = new Scheduler(topo, maxThreads);
        s.enableDebug();

        runNone(topo, s, maxThreads);
    }

    private PresetListTopology generate() throws TopologyParseException {
        Random r = new Random(n);

        PresetListTopology topo = new PresetListTopology();

        Set<Integer> takenThreads = new HashSet<>();
        Map<Integer, Integer> coreToPackage = new HashMap<>();

        for (int c = 0; c < 24; c++) {
            int tId;
            do {
                tId = r.nextInt(32);
            } while (!takenThreads.add(tId));

            int cId = r.nextInt(16);

            int pId;
            if (coreToPackage.containsKey(cId)) {
                pId = coreToPackage.get(cId);
            } else {
                pId = r.nextInt(4);
                coreToPackage.put(cId, pId);
            }

            topo.add(pId, cId, tId);
        }
        topo.finish();
        return topo;
    }
}
