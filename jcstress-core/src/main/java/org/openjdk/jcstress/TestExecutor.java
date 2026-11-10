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
package org.openjdk.jcstress;

import org.openjdk.jcstress.infra.Status;
import org.openjdk.jcstress.infra.collectors.TestResult;
import org.openjdk.jcstress.infra.collectors.TestResultCollector;
import org.openjdk.jcstress.infra.runners.*;
import org.openjdk.jcstress.link.BinaryLinkServer;
import org.openjdk.jcstress.link.ServerListener;
import org.openjdk.jcstress.os.*;
import org.openjdk.jcstress.util.*;
import org.openjdk.jcstress.vm.VMSupport;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages test execution for the entire run.
 * <p>
 * This executor is deliberately single-threaded for two reasons:
 * a) Tests are heavily multithreaded and spawning new threads here may
 * deplete the thread budget sooner rather than later;
 * b) Dead-locks in scheduling logic are more visible without threads;
 */
public class TestExecutor {

    static final AtomicInteger ID = new AtomicInteger();

    private final BinaryLinkServer server;
    private final Verbosity verbosity;
    private final TestResultCollector sink;
    private final Scheduler scheduler;

    private final Map<Integer, VM> vmByToken;
    private final Object notifyLock;

    private final AtomicInteger jvmsStarting;
    private final AtomicInteger jvmsRunning;
    private final AtomicInteger jvmsFinishing;

    private final ExecutorService supportTasks;

    private final TimeBudget timeBudget;

    public TestExecutor(Verbosity verbosity, TestResultCollector sink, Scheduler scheduler, TimeBudget tb) throws IOException {
        this.verbosity = verbosity;
        this.sink = sink;
        this.vmByToken = new ConcurrentHashMap<>();
        this.scheduler = scheduler;
        this.notifyLock = new Object();

        server = new BinaryLinkServer(new ServerListener() {
            @Override
            public ForkedTestConfig onJobRequest(int token) {
                return vmByToken.get(token).jobRequest();
            }

            @Override
            public void onResult(int token, TestResult result) {
                vmByToken.get(token).recordResult(result);
                notifyChanged();
            }
        });

        this.jvmsStarting = new AtomicInteger();
        this.jvmsRunning = new AtomicInteger();
        this.jvmsFinishing = new AtomicInteger();

        this.supportTasks = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger id = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("jcstress-vm-support-" + id.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

        this.timeBudget = tb;
    }

    private void awaitNotification() {
        synchronized (notifyLock) {
            try {
                // Wait one second and then unblock for extra safety
                notifyLock.wait(1000);
            } catch (InterruptedException e) {
                // Do nothing
            }
        }
    }

    private void notifyChanged() {
        synchronized (notifyLock) {
            notifyLock.notifyAll();
        }
    }

    public void runAll(List<TestConfig> configs) {
        // Build the scheduling classes maps
        Multimap<SchedulingClass, TestConfig> byScl = new HashMultimap<>();
        List<SchedulingClass> scls = new ArrayList<>();

        {
            Set<SchedulingClass> uniqueScls = new HashSet<>();

            for (TestConfig cfg : configs) {
                byScl.put(cfg.getSchedulingClass(), cfg);
                uniqueScls.add(cfg.getSchedulingClass());
            }

            // Try the largest scheduling classes first
            scls.addAll(uniqueScls);
            Collections.sort(scls, Comparator.comparing(SchedulingClass::numActors).reversed());
        }

        while (!byScl.isEmpty()) {

            // Roll over the scheduling classes and try to greedily cram most
            // of the tasks for it. This exits when no scheduling classes can fit
            // the current state of the machine.
            for (SchedulingClass scl : scls) {
                while (byScl.containsKey(scl)) {
                    CPUMap cpuMap = scheduler.tryAcquire(scl);
                    if (cpuMap == null) {
                        // No more scheduling for this class
                        break;
                    }

                    TestConfig cfg = byScl.removeLast(scl);
                    cfg.setCPUMap(cpuMap);
                    int token = ID.incrementAndGet();
                    VM vm = new VM(server.getHost(), server.getPort(), token, cfg, cpuMap);
                    vmByToken.put(token, vm);
                    supportTasks.submit(vm::start);
                }
            }

            // Wait until any VM finishes before rescheduling
            while (!processReadyVMs()) {
                awaitNotification();
            }
        }

        // Wait until all threads are done, which means everything got processed
        while (!vmByToken.isEmpty()) {
            while (!processReadyVMs()) {
                awaitNotification();
            }
        }

        supportTasks.shutdown();
        try {
            supportTasks.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            // Do nothing
        }

        server.terminate();
    }

    private boolean processReadyVMs() {
        boolean reclaimed = false;
        for (VM vm : vmByToken.values()) {
            if (vm.checkCompleted()) {
                supportTasks.submit(() -> vm.finish(sink));
                vmByToken.remove(vm.token, vm);
                scheduler.release(vm.cpuMap);
                reclaimed = true;
            }
        }
        return reclaimed;
    }

    public int getCpus() {
        return scheduler.getCpus();
    }

    public int getJVMsStarting() {
        return jvmsStarting.get();
    }

    public int getJVMsRunning() {
        return jvmsRunning.get();
    }

    public int getJVMsFinishing() {
        return jvmsFinishing.get();
    }

    private class VM {
        private final String host;
        private final int port;
        private final int token;
        private File compilerDirectives;
        private final TestConfig task;
        private final CPUMap cpuMap;
        private Process process;
        private boolean processed;
        private IOException pendingException;
        private TestResult result;
        private Future<List<String>> errs;
        private Future<List<String>> outs;
        private boolean isStarted;

        public VM(String host, int port, int token, TestConfig task, CPUMap cpuMap) {
            this.host = host;
            this.port = port;
            this.token = token;
            this.cpuMap = cpuMap;
            this.task = task;
        }

        void generateDirectives() throws IOException {
            compilerDirectives = File.createTempFile("jcstress", "directives");

            // Register these files for removal in case we terminate through the uncommon path
            compilerDirectives.deleteOnExit();

            PrintWriter pw = new PrintWriter(compilerDirectives);
            task.generateDirectives(pw, verbosity);
            pw.close();
        }

        synchronized void start() {
            jvmsStarting.incrementAndGet();

            if (VMSupport.compilerDirectivesAvailable()) {
                try {
                    generateDirectives();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            try {
                List<String> command = new ArrayList<>();

                if (OSSupport.taskSetAvailable()) {
                    String map = cpuMap.globalAffinityMap();
                    if (!map.isEmpty()) {
                        command.add("taskset");
                        command.add("-c");
                        command.add(map);
                    }
                }

                // basic Java line
                command.addAll(VMSupport.getJavaInvokeLine());

                // additional flags from OS support
                command.addAll(OSSupport.getJavaInvokeArguments());

                // jvm args
                command.addAll(task.jvmArgs);

                if (VMSupport.compilerDirectivesAvailable()) {
                    command.add("-XX:CompilerDirectivesFile=" + compilerDirectives.getAbsolutePath());
                }

                boolean localAffinity = task.shClass.mode() == AffinityMode.LOCAL;
                if (localAffinity && VMSupport.enableNativeAccessAvailable()) {
                    command.add(VMSupport.enableNativeAccessOpt());
                }

                command.add(ForkedMain.class.getName());

                // notify the forked VM whether we want the local affinity initialized
                command.add(Boolean.toString(localAffinity));

                command.add(host);
                command.add(String.valueOf(port));

                // which config should the forked VM pull?
                command.add(String.valueOf(token));

                ProcessBuilder pb = new ProcessBuilder(command);
                process = pb.start();

                // start the stream drainers and read the streams into memory;
                // makes little sense to write them to files, since we would be
                // reading them back soon anyway
                errs = supportTasks.submit(new InputStreamCollector(process.getErrorStream()));
                outs = supportTasks.submit(new InputStreamCollector(process.getInputStream()));

            } catch (IOException ex) {
                pendingException = ex;
            }
            isStarted = true;
            jvmsStarting.decrementAndGet();
            jvmsRunning.incrementAndGet();
        }

        public synchronized ForkedTestConfig jobRequest() {
            if (processed) {
                return null;
            }
            processed = true;
            timeBudget.startTest();
            return new ForkedTestConfig(task, timeBudget.targetTestTimeMs());
        }

        public synchronized boolean checkCompleted() {
            // Not yet started
            if (!isStarted) {
                return false;
            }

            // There is a pending exception that terminated the target VM.
            if (pendingException != null) {
                return true;
            }

            // Process is still alive, no need to ask about the status.
            if (result == null && process.isAlive()) {
                return false;
            }

            return true;
        }

        public synchronized void finish(TestResultCollector sink) {
            jvmsRunning.decrementAndGet();
            jvmsFinishing.incrementAndGet();

            if (!checkCompleted()) {
                throw new IllegalStateException("Should be completed");
            }

            // There is a pending exception that terminated the target VM.
            if (pendingException != null) {
                result = new TestResult(Status.VM_ERROR);
                result.addMessages(pendingException);
                result.setConfig(task);
                sink.add(result);
                return;
            }

            // Try to poll the exit code, and fail if it's not zero.
            try {
                int ecode = process.waitFor();

                if (ecode != 0) {
                    result = new TestResult(Status.VM_ERROR);
                    result.addMessage("Failed with error code " + ecode);
                }
                if (result == null) {
                    result = new TestResult(Status.VM_ERROR);
                    result.addMessage("Harness error, no result generated");
                }
                result.addVMOuts(outs.get());
                result.addVMErrs(errs.get());
                result.setConfig(task);
                sink.add(result);
            } catch (InterruptedException | ExecutionException ex) {
                result = new TestResult(Status.VM_ERROR);
                result.addMessages(ex);
                result.setConfig(task);
                sink.add(result);
            } finally {
                // The process is definitely dead, remove the temporary files.
                if (compilerDirectives != null) {
                    compilerDirectives.delete();
                }
            }

            jvmsFinishing.decrementAndGet();
            timeBudget.finishTest();
        }

        public synchronized void recordResult(TestResult r) {
            if (result != null) {
                throw new IllegalStateException("VM had already published a result.");
            }
            result = r;
        }
    }

}
