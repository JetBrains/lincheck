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
package org.openjdk.jcstress.os.topology;

import org.junit.*;
import org.openjdk.jcstress.util.FileUtils;
import org.openjdk.jcstress.vm.VMSupport;

import java.io.IOException;

public class LinuxProcfsTopologyTest extends AbstractTopologyTest {

    @Before
    public void preconditions() {
        Assume.assumeTrue(VMSupport.isLinux());
    }

    @Test
    @Ignore // Comment this to test that current environment is parsable.
    public void test_Current() throws TopologyParseException {
        new LinuxProcfsTopology();
    }

    @Test
    public void test_Saved_1() throws TopologyParseException, IOException {
        String s = FileUtils.copyFileToTemp("/topology/cpuinfo-1.txt", "jcstress", "test");
        LinuxProcfsTopology topo = new LinuxProcfsTopology(s);

        Assert.assertEquals(1,  topo.nodesPerSystem());
        Assert.assertEquals(32, topo.coresPerNode());
        Assert.assertEquals(2,  topo.threadsPerCore());
        Assert.assertEquals(32, topo.totalCores());
        Assert.assertEquals(64, topo.totalThreads());

        for (int t = 0; t < topo.totalThreads(); t++) {
            Assert.assertEquals(0, topo.threadToNode(t));
            Assert.assertEquals(t % topo.coresPerNode(), topo.threadToCore(t));
        }

        checkGenericInvariants(topo);
    }

    @Test
    public void test_Saved_2() throws IOException {
        String s = FileUtils.copyFileToTemp("/topology/cpuinfo-2.txt", "jcstress", "test");

        try {
            new LinuxProcfsTopology(s);
            Assert.fail("Should have failed");
        } catch (TopologyParseException topo) {
            // Should fail
        }
    }

    @Test
    public void test_Saved_3() throws IOException {
        String s = FileUtils.copyFileToTemp("/topology/cpuinfo-3.txt", "jcstress", "test");

        try {
            new LinuxProcfsTopology(s);
            Assert.fail("Should have failed");
        } catch (TopologyParseException topo) {
            // Should fail
        }
    }

    @Test
    public void test_Saved_4() throws IOException {
        String s = FileUtils.copyFileToTemp("/topology/cpuinfo-4.txt", "jcstress", "test");

        try {
            new LinuxProcfsTopology(s);
            Assert.fail("Should have failed");
        } catch (TopologyParseException topo) {
            // Should fail
        }
    }

    @Test
    public void test_Saved_5() throws IOException {
        String s = FileUtils.copyFileToTemp("/topology/cpuinfo-5.txt", "jcstress", "test");

        try {
            new LinuxProcfsTopology(s);
            Assert.fail("Should have failed");
        } catch (TopologyParseException topo) {
            // Should fail
        }
    }

    @Test
    public void test_Saved_6() throws IOException {
        String s = FileUtils.copyFileToTemp("/topology/cpuinfo-6.txt", "jcstress", "test");

        try {
            new LinuxProcfsTopology(s);
            Assert.fail("Should have failed");
        } catch (TopologyParseException topo) {
            // Should fail
        }
    }

    @Test
    public void test_Saved_7() throws IOException {
        String s = FileUtils.copyFileToTemp("/topology/cpuinfo-7.txt", "jcstress", "test");

        try {
            new LinuxProcfsTopology(s);
            Assert.fail("Should have failed");
        } catch (TopologyParseException topo) {
            // Should fail
        }
    }

}
