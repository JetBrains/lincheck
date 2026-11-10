/*
 * Copyright (c) 2005, 2014, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.jcstress;

import org.openjdk.jcstress.infra.Status;
import org.openjdk.jcstress.infra.collectors.TestResult;
import org.openjdk.jcstress.infra.runners.ForkedTestConfig;
import org.openjdk.jcstress.infra.runners.Runner;
import org.openjdk.jcstress.infra.runners.VoidThread;
import org.openjdk.jcstress.link.BinaryLinkClient;
import org.openjdk.jcstress.os.AffinitySupport;
import org.openjdk.jcstress.util.StringUtils;
import org.openjdk.jcstress.vm.AllocProfileSupport;

import java.lang.reflect.Constructor;

/**
 * Entry point for the forked VM run.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class ForkedMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalStateException("Expected three arguments");
        }

        // Pre-initialize the allocation profiling support, so that infrastructure
        // code does not have to do this on critical path during the execution.
        new WarmupAllocProfileTask().start();

        boolean initLocalAffinity = Boolean.parseBoolean(args[0]);

        if (initLocalAffinity) {
            // Pre-initialize the affinity support and threads, so that workers
            // do not have to do this on critical paths during the execution.
            // This also runs when the rest of the infrastructure starts up.
            new WarmupAffinityTask().start();
        }

        String host = args[1];
        int port = Integer.parseInt(args[2]);
        int token = Integer.parseInt(args[3]);

        BinaryLinkClient link = new BinaryLinkClient(host, port);

        ForkedTestConfig config = link.jobRequest(token);

        TestResult result;
        boolean forceExit = false;

        try {
            Class<?> aClass = Class.forName(config.generatedRunnerName);
            Constructor<?> cnstr = aClass.getConstructor(ForkedTestConfig.class);
            Runner<?> o = (Runner<?>) cnstr.newInstance(config);
            result = o.run();
            forceExit = o.forceExit();
        } catch (ClassFormatError | NoClassDefFoundError | NoSuchMethodError | NoSuchFieldError e) {
            result = new TestResult(Status.API_MISMATCH);
            result.addMessages(e);
        } catch (Throwable ex) {
            result = new TestResult(Status.TEST_ERROR);
            result.addMessages(ex);
        }

        if (forceExit) {
            result.addMessage("Have stale threads, forcing VM to exit for proper cleanup.");
        }

        link.doneResult(token, result);

        if (forceExit) {
            System.exit(0);
        }
    }

    private static class WarmupAffinityTask extends VoidThread {
        public WarmupAffinityTask() {
            super("Warmup Affinity");
        }

        @Override
        protected void internalRun() {
            try {
                AffinitySupport.tryBind();
            } catch (Exception e) {
                // Do not care
            }
        }

        @Override
        public void purge() {
            // Do nothing
        }
    }

    private static class WarmupAllocProfileTask extends VoidThread {
        public WarmupAllocProfileTask() {
            super("Warmup Alloc Profile");
        }

        @Override
        protected void internalRun() {
            try {
                AllocProfileSupport.getAllocatedBytes();
            } catch (Exception e) {
                // Do not care
            }
        }

        @Override
        public void purge() {
            // Do nothing
        }
    }

}
