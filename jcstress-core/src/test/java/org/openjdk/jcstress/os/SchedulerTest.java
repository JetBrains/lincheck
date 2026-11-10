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

import org.junit.Assert;
import org.junit.Test;
import org.openjdk.jcstress.os.topology.*;

public class SchedulerTest {

    @Test
    public void trivial_differentCores() throws TopologyParseException {
        Topology t = new PresetRegularTopology(2, 4, 4);
        Scheduler s = new Scheduler(t, t.totalThreads());

        SchedulingClass scl = new SchedulingClass(AffinityMode.LOCAL, 2, NodeType.PACKAGE);
        scl.setNode(0, 0);
        scl.setNode(1, 0);
        scl.setCore(0, 0);
        scl.setCore(1, 1);

        CPUMap cpuMap = s.tryAcquire(scl);
        Assert.assertNotNull("Should be scheduled", cpuMap);
        int[] schedule = cpuMap.actorThreads();

        Assert.assertTrue("Should be scheduled at the different cores",
                t.threadToCore(schedule[0]) != t.threadToCore(schedule[1]));
    }

    @Test
    public void trivial_sameCore() throws TopologyParseException {
        Topology t = new PresetRegularTopology(2, 4, 4);
        Scheduler s = new Scheduler(t, t.totalThreads());

        SchedulingClass scl = new SchedulingClass(AffinityMode.LOCAL, 2, NodeType.PACKAGE);
        scl.setNode(0, 0);
        scl.setNode(1, 0);
        scl.setCore(0, 0);
        scl.setCore(1, 0);

        CPUMap cpuMap = s.tryAcquire(scl);
        Assert.assertNotNull("Should be scheduled", cpuMap);
        int[] schedule = cpuMap.actorThreads();

        Assert.assertTrue("Should be scheduled at the same core",
                t.threadToCore(schedule[0]) == t.threadToCore(schedule[1]));
    }

    @Test
    public void trivial_differentPackages() throws TopologyParseException {
        Topology t = new PresetRegularTopology(2, 4, 4);
        Scheduler s = new Scheduler(t, t.totalThreads());

        SchedulingClass scl = new SchedulingClass(AffinityMode.LOCAL, 2, NodeType.PACKAGE);
        scl.setNode(0, 0);
        scl.setNode(1, 1);
        scl.setCore(0, 0);
        scl.setCore(1, 1);

        CPUMap cpuMap = s.tryAcquire(scl);
        Assert.assertNotNull("Should be scheduled", cpuMap);
        int[] schedule = cpuMap.actorThreads();

        Assert.assertTrue("Should be scheduled at the different packages",
                t.threadToNode(schedule[0]) != t.threadToNode(schedule[1]));
        Assert.assertTrue("Should be scheduled at the different cores",
                t.threadToCore(schedule[0]) != t.threadToCore(schedule[1]));
    }

    @Test
    public void testPermutations_1_1() {
        int[][] ints = Scheduler.classPermutation(1, 1);
        Assert.assertEquals(1, ints.length);
        Assert.assertArrayEquals(new int[] {0}, ints[0]);
    }

    @Test
    public void testPermutations_1_2() {
        int[][] ints = Scheduler.classPermutation(1, 2);
        Assert.assertEquals(1, ints.length);
        Assert.assertArrayEquals(new int[] {0}, ints[0]);
    }

    @Test
    public void testPermutations_2_1() {
        int[][] ints = Scheduler.classPermutation(2, 1);

        Assert.assertEquals(1, ints.length);
        Assert.assertArrayEquals(new int[] {0, 0}, ints[0]);
    }

    @Test
    public void testPermutations_2_2() {
        int[][] ints = Scheduler.classPermutation(2, 2);

        Assert.assertEquals(2, ints.length);
        Assert.assertArrayEquals(new int[] {0, 0}, ints[0]);
        Assert.assertArrayEquals(new int[] {0, 1}, ints[1]);
    }

    @Test
    public void testPermutations_2_3() {
        int[][] ints = Scheduler.classPermutation(2, 3);

        Assert.assertEquals(2, ints.length);
        Assert.assertArrayEquals(new int[] {0, 0}, ints[0]);
        Assert.assertArrayEquals(new int[] {0, 1}, ints[1]);
    }

    @Test
    public void testPermutations_3_1() {
        int[][] ints = Scheduler.classPermutation(3, 1);

        Assert.assertEquals(1, ints.length);
        Assert.assertArrayEquals(new int[] {0, 0, 0}, ints[0]);
    }

    @Test
    public void testPermutations_3_2() {
        int[][] ints = Scheduler.classPermutation(3, 2);

        Assert.assertEquals(4, ints.length);
        Assert.assertArrayEquals(new int[] {0, 0, 0}, ints[0]);
        Assert.assertArrayEquals(new int[] {0, 1, 0}, ints[1]);
        Assert.assertArrayEquals(new int[] {0, 0, 1}, ints[2]);
        Assert.assertArrayEquals(new int[] {0, 1, 1}, ints[3]);
    }

    @Test
    public void testPermutations_3_3() {
        int[][] ints = Scheduler.classPermutation(3, 3);

        Assert.assertEquals(5, ints.length);
        Assert.assertArrayEquals(new int[] {0, 0, 0}, ints[0]);
        Assert.assertArrayEquals(new int[] {0, 1, 0}, ints[1]);
        Assert.assertArrayEquals(new int[] {0, 0, 1}, ints[2]);
        Assert.assertArrayEquals(new int[] {0, 1, 1}, ints[3]);
        Assert.assertArrayEquals(new int[] {0, 1, 2}, ints[4]);
    }

    @Test
    public void testPermutations_3_4() {
        int[][] ints = Scheduler.classPermutation(3, 4);

        Assert.assertEquals(5, ints.length);
        Assert.assertArrayEquals(new int[] {0, 0, 0}, ints[0]);
        Assert.assertArrayEquals(new int[] {0, 1, 0}, ints[1]);
        Assert.assertArrayEquals(new int[] {0, 0, 1}, ints[2]);
        Assert.assertArrayEquals(new int[] {0, 1, 1}, ints[3]);
        Assert.assertArrayEquals(new int[] {0, 1, 2}, ints[4]);
    }

    @Test
    public void testPermutations_4_1() {
        int[][] ints = Scheduler.classPermutation(4, 1);

        Assert.assertEquals(1, ints.length);

        Assert.assertArrayEquals(new int[] {0, 0, 0, 0}, ints[0]);
    }

    @Test
    public void testPermutations_4_2() {
        int[][] ints = Scheduler.classPermutation(4, 2);

        Assert.assertEquals(8, ints.length);

        Assert.assertArrayEquals(new int[] {0, 0, 0, 0}, ints[0]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 0}, ints[1]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 0}, ints[2]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 0}, ints[3]);
        Assert.assertArrayEquals(new int[] {0, 0, 0, 1}, ints[4]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 1}, ints[5]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 1}, ints[6]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 1}, ints[7]);
    }

    @Test
    public void testPermutations_4_3() {
        int[][] ints = Scheduler.classPermutation(4, 3);

        Assert.assertEquals(14, ints.length);

        Assert.assertArrayEquals(new int[] {0, 0, 0, 0}, ints[0]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 0}, ints[1]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 0}, ints[2]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 0}, ints[3]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 0}, ints[4]);
        Assert.assertArrayEquals(new int[] {0, 0, 0, 1}, ints[5]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 1}, ints[6]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 1}, ints[7]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 1}, ints[8]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 1}, ints[9]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 2}, ints[10]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 2}, ints[11]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 2}, ints[12]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 2}, ints[13]);
    }

    @Test
    public void testPermutations_4_4() {
        int[][] ints = Scheduler.classPermutation(4, 4);

        Assert.assertEquals(15, ints.length);

        Assert.assertArrayEquals(new int[] {0, 0, 0, 0}, ints[0]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 0}, ints[1]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 0}, ints[2]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 0}, ints[3]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 0}, ints[4]);
        Assert.assertArrayEquals(new int[] {0, 0, 0, 1}, ints[5]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 1}, ints[6]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 1}, ints[7]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 1}, ints[8]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 1}, ints[9]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 2}, ints[10]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 2}, ints[11]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 2}, ints[12]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 2}, ints[13]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 3}, ints[14]);
    }

    @Test
    public void testPermutations_4_5() {
        int[][] ints = Scheduler.classPermutation(4, 5);

        Assert.assertEquals(15, ints.length);

        Assert.assertArrayEquals(new int[] {0, 0, 0, 0}, ints[0]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 0}, ints[1]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 0}, ints[2]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 0}, ints[3]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 0}, ints[4]);
        Assert.assertArrayEquals(new int[] {0, 0, 0, 1}, ints[5]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 1}, ints[6]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 1}, ints[7]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 1}, ints[8]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 1}, ints[9]);
        Assert.assertArrayEquals(new int[] {0, 1, 0, 2}, ints[10]);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 2}, ints[11]);
        Assert.assertArrayEquals(new int[] {0, 1, 1, 2}, ints[12]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 2}, ints[13]);
        Assert.assertArrayEquals(new int[] {0, 1, 2, 3}, ints[14]);
    }

}
