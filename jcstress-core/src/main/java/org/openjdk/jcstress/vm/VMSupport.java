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
package org.openjdk.jcstress.vm;

import org.openjdk.jcstress.Options;
import org.openjdk.jcstress.util.ArrayUtils;
import org.openjdk.jcstress.util.InputStreamDrainer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class VMSupport {

    private static final List<String> GLOBAL_JVM_FLAGS = new ArrayList<>();
    private static final List<String> C2_STRESS_JVM_FLAGS = new ArrayList<>();
    private static final List<String> C2_ONLY_STRESS_JVM_FLAGS = new ArrayList<>();

    private static final List<Config> AVAIL_JVM_CONFIGS = new ArrayList<>();
    private static volatile boolean THREAD_SPIN_WAIT_AVAILABLE;
    private static volatile boolean COMPILER_DIRECTIVES_AVAILABLE;
    private static volatile boolean PRINT_ASSEMBLY_AVAILABLE;
    private static volatile boolean STRESS_SEED_AVAILABLE;

    private static volatile boolean C1_AVAILABLE;
    private static volatile boolean C2_AVAILABLE;
    private static volatile boolean COMPILERS_AVAILABLE;

    private static volatile boolean BIASED_LOCKING_AVAILABLE;

    private static volatile boolean ENABLE_NATIVE_ACCESS_AVAILABLE;
    private static final String ENABLE_NATIVE_ACCESS_OPT = "--enable-native-access=ALL-UNNAMED";

    public static boolean spinWaitHintAvailable() {
        return THREAD_SPIN_WAIT_AVAILABLE;
    }

    public static boolean compilerDirectivesAvailable() {
        return COMPILER_DIRECTIVES_AVAILABLE;
    }

    public static boolean printAssemblyAvailable() {
        return PRINT_ASSEMBLY_AVAILABLE;
    }

    public static boolean c1Available() {
        return C1_AVAILABLE;
    }

    public static boolean c2Available() {
        return C2_AVAILABLE;
    }

    public static boolean enableNativeAccessAvailable() {
        return ENABLE_NATIVE_ACCESS_AVAILABLE;
    }

    public static String enableNativeAccessOpt() {
        return ENABLE_NATIVE_ACCESS_OPT;
    }

    public static void initFlags(Options opts) {
        System.out.println("Initializing and probing the target VM:");
        System.out.println(" (all failures are non-fatal, but may affect testing accuracy)");
        System.out.println();

        detect("Unlocking diagnostic VM options",
                true,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:+UnlockDiagnosticVMOptions"
        );

        detect("Disabling performance data collection",
                true,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-UsePerfData"
        );

        C1_AVAILABLE = detect("Checking for C1 availability",
                true,
                SimpleTestMain.class,
                null,
                "-XX:+C1ProfileCalls"
        );

        C2_AVAILABLE = detect("Checking for C2 availability",
                true,
                SimpleTestMain.class,
                null,
                "-XX:+UseLoopPredicate"
        );

        COMPILERS_AVAILABLE = C1_AVAILABLE || C2_AVAILABLE;

        BIASED_LOCKING_AVAILABLE = detect("Checking for biased locking support",
                false,
                SimpleTestMain.class,
                null,
                "-XX:+UseBiasedLocking"
        );

        ENABLE_NATIVE_ACCESS_AVAILABLE = detect("Checking for native access warnings",
                false,
                SimpleTestMain.class,
                null,
                ENABLE_NATIVE_ACCESS_OPT
        );

        // Tests are supposed to run in a very tight memory constraints:
        // the test objects are small and reused where possible. The footprint
        // testing machinery would select appropriate stride sizes to fit the heap.
        // Users can override this to work on smaller/larger machines, but it should
        // not be necessary, as even the smallest machines usually have more than 256M
        // of system memory per CPU.

        int heap = opts.getHeapPerForkMb();
        detect("Trimming down the VM heap size to " + heap + "M",
                true,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-Xms" + heap + "M", "-Xmx" + heap + "M");

        // After heap size is set, check if we can pre-touch it. This would allow
        // tests to run in fully committed heap without experiencing the occasional
        // memory stalls. This also provides better safety in face of OS OOM-killers.
        // On large heaps, this might take a while, so users are allowed to disable
        // pre-touch for better performance.

        if (opts.isPretouchHeap()) {
            detect("Enabling Java heap pre-touch",
                    true,
                    SimpleTestMain.class,
                    GLOBAL_JVM_FLAGS,
                    "-XX:+AlwaysPreTouch"
            );
        }

        // The tests are usually not GC heavy. The minimum amount of threads a jcstress
        // test uses is 2, so we can expect the CPU affinity machinery to allocate at
        // least 2 CPUs per fork. This gives us the upper bound for the number of GC threads: 2,
        // otherwise we risk oversubscribing the forked VM.
        //
        // We could, theoretically, drop the number of GC threads to 1, but GC ergonomics
        // sometimes decides to switch to single-threaded mode in some GC implementations
        // (e.g. for reference processing), and it would make sense to let GC run in multi-threaded
        // modes instead.

        detect("Trimming down the number of parallel GC threads",
                true,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:ParallelGCThreads=2"
        );

        detect("Trimming down the number of concurrent GC threads",
                true,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:ConcGCThreads=2"
        );

        detect("Trimming down the number of G1 concurrent refinement GC threads",
                true,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:G1ConcRefinementThreads=2"
        );

        if (COMPILERS_AVAILABLE) {
            detect("Trimming down the number of compiler threads",
                    true,
                    SimpleTestMain.class,
                    GLOBAL_JVM_FLAGS,
                    "-XX:CICompilerCount=2" // This is the absolute minimum for tiered configurations
            );
        }

        detect("Testing @Contended works on all results and infra objects",
                true,
                ContendedTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-RestrictContended"
        );

        detect("Unlocking debug information for non-safepoints",
                true,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:+DebugNonSafepoints"
        );

        detect("Disabling verification on exit",
                true,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-VerifyBeforeExit"
        );

        detect("Disabling Thread SMR statistics",
                true,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-EnableThreadSMRStatistics"
        );

        detect("Disabling debug JVM zapping: unused heap",
                false,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-ZapUnusedHeapArea"
        );

        detect("Disabling debug JVM zapping: TLABs",
                false,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-ZapTLAB"
        );

        detect("Disabling debug JVM zapping: filler objects",
                false,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-ZapFillerObjects"
        );

        detect("Disabling debug JVM zapping: resource area",
                false,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-ZapResourceArea"
        );

        detect("Disabling debug JVM zapping: VM handle area",
                false,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-ZapVMHandleArea"
        );

        detect("Disabling debug JVM zapping: stack segments",
                false,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-ZapStackSegments"
        );

        detect("Disabling debug JVM verification: compilation dependencies",
                false,
                SimpleTestMain.class,
                GLOBAL_JVM_FLAGS,
                "-XX:-VerifyDependencies"
        );

        if (C2_AVAILABLE) {
            detect("Unlocking C2 local code motion randomizer",
                    true,
                    SimpleTestMain.class,
                    C2_STRESS_JVM_FLAGS,
                    "-XX:+StressLCM"
            );

            detect("Unlocking C2 global code motion randomizer",
                    true,
                    SimpleTestMain.class,
                    C2_STRESS_JVM_FLAGS,
                    "-XX:+StressGCM"
            );

            detect("Unlocking C2 iterative global value numbering randomizer",
                    true,
                    SimpleTestMain.class,
                    C2_STRESS_JVM_FLAGS,
                    "-XX:+StressIGVN"
            );

            detect("Unlocking C2 conditional constant propagation randomizer",
                    true,
                    SimpleTestMain.class,
                    C2_STRESS_JVM_FLAGS,
                    "-XX:+StressCCP"
            );

            detect("Unlocking C2 incremental inlining randomizer",
                    true,
                    SimpleTestMain.class,
                    C2_STRESS_JVM_FLAGS,
                    "-XX:+StressIncrementalInlining"
            );

            detect("Unlocking C2 macro expansion randomizer",
                    true,
                    SimpleTestMain.class,
                    C2_STRESS_JVM_FLAGS,
                    "-XX:+StressMacroExpansion"
            );

            STRESS_SEED_AVAILABLE = detect("Checking if C2 randomizers accept stress seed",
                    true,
                    SimpleTestMain.class,
                    null,
                    "-XX:StressSeed=42"
            );

            C2_ONLY_STRESS_JVM_FLAGS.add("-XX:-TieredCompilation");
            C2_ONLY_STRESS_JVM_FLAGS.addAll(C2_STRESS_JVM_FLAGS);
        }

        detect("Testing allocation profiling",
                true,
                AllocProfileMain.class,
                null
        );

        THREAD_SPIN_WAIT_AVAILABLE =
                detect("Testing Thread.onSpinWait",
                        true,
                        ThreadSpinWaitTestMain.class,
                        null
                );

        if (COMPILERS_AVAILABLE) {
            PRINT_ASSEMBLY_AVAILABLE =
                detect("Testing PrintAssembly",
                        true,
                        SimpleTestMain.class,
                        null,
                        "-XX:+PrintAssembly"
                );

            try {
                File temp = File.createTempFile("jcstress", "directives");

                PrintWriter pw = new PrintWriter(temp);
                pw.println("[ { match: \"*::*\", PrintInlining: true } ]");
                pw.close();

                COMPILER_DIRECTIVES_AVAILABLE =
                        detect("Testing compiler directives",
                                true,
                                SimpleTestMain.class,
                                null,
                                "-XX:CompilerDirectivesFile=" + temp.getAbsolutePath()
                        );

                temp.delete();
            } catch (IOException e) {
                // Do nothing.
            }
        }

        System.out.println();
    }

    private static boolean detect(String label, boolean expectPass, Class<?> mainClass, List<String> list, String... opts) {
        try {
            String[] arguments = ArrayUtils.concat(opts, mainClass.getName());
            tryWith(arguments);
            if (list != null) {
                list.addAll(Arrays.asList(opts));
            }
            System.out.printf("----- %s %s%n", "[OK]", label);
            return true;
        } catch (VMSupportException ex) {
            System.out.printf("----- %s %s%n", "[N/A]", label);
            if (expectPass) {
                System.out.println(ex.getMessage());
            }
            return false;
        }
    }

    private static Config prependArgs(Config orig, String... args) {
        return prependArgs(orig, Arrays.asList(args));
    }

    private static Config prependArgs(Config orig, List<String> args) {
        List<String> l = new ArrayList<>();
        l.addAll(args);
        l.addAll(orig.origArgs());
        return new Config(l, orig.availableRuntimes(), orig.requiredRuntimes(), orig.stress());
    }

    private static Config cleanArgs(Config orig) {
        List<String> l = orig.args.stream()
            .filter(s -> !s.startsWith("-agentlib:jdwp"))
            .collect(Collectors.toList());
        return new Config(l, orig.availableRuntimes(), orig.requiredRuntimes(), orig.stress());
    }

    public static void detectAvailableVMConfigs(boolean splitCompilation, List<String> jvmArgs, List<String> jvmArgsPrepend) {
        System.out.println("Probing what VM configurations are available:");
        System.out.println(" (failures are non-fatal, but may miss some interesting cases)");

        LinkedHashSet<Config> configs = new LinkedHashSet<>();

        if (!jvmArgs.isEmpty()) {
            configs.add(new Config(jvmArgs, Runtimes.all(), Runtimes.none(), false));
        } else {
            configs.add(new Config(Collections.singletonList("-Xint"), Runtimes.intp(), Runtimes.none(), false));
            if (C1_AVAILABLE) {
                configs.add(new Config(Collections.singletonList("-XX:TieredStopAtLevel=1"), Runtimes.intpC1(), Runtimes.none(), false));
            }
            if (C2_AVAILABLE) {
                configs.add(new Config(Collections.singletonList("-XX:-TieredCompilation"), Runtimes.intpC2(), Runtimes.none(), false));
            }

            List<String> c2StressFlags;
            if (splitCompilation && COMPILER_DIRECTIVES_AVAILABLE) {
                System.out.println(" (split compilation is requested and compiler directives are available)");
                configs.add(new Config(Collections.emptyList(), Runtimes.all(), Runtimes.none(), false));
                c2StressFlags = C2_STRESS_JVM_FLAGS;
            } else {
                c2StressFlags = C2_ONLY_STRESS_JVM_FLAGS;
            }

            // Mix in appropriate C2 stress options
            if (C2_AVAILABLE) {
                configs.add(new Config(c2StressFlags, Runtimes.all(), Runtimes.C2(), true));
            }
        }

        // Mix in locking arguments, if available
        if (BIASED_LOCKING_AVAILABLE) {
            LinkedHashSet<Config> newConfigs = new LinkedHashSet<>();
            for (Config c : configs) {
                newConfigs.add(prependArgs(c, "-XX:+UseBiasedLocking"));
                newConfigs.add(prependArgs(c, "-XX:-UseBiasedLocking"));
            }
            configs = newConfigs;
        }

        // Mix in input arguments, if available
        List<String> inputArgs = new ArrayList<>();

        try {
            inputArgs.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (InternalError e) {
            System.out.println("Warning: cannot get input arguments: " + e.getMessage());
        }

        if (!inputArgs.isEmpty()) {
            configs = configs.stream()
                    .map(c -> prependArgs(c, inputArgs))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // Filter out unwanted arguments.
        configs = configs.stream()
                .map(c -> cleanArgs(c))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // Mix in prepends, if available
        if (jvmArgsPrepend != null) {
            configs = configs.stream()
                    .map(c -> prependArgs(c, jvmArgsPrepend))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        System.out.println();

        for (Config config : configs) {
            List<String> args = config.origArgs();
            try {
                List<String> line = new ArrayList<>(args);
                line.add(SimpleTestMain.class.getName());
                tryWith(line.toArray(new String[0]));
                AVAIL_JVM_CONFIGS.add(config);
                System.out.printf("----- [OK] %s%n", args);
            } catch (VMSupportException e) {
                System.out.printf("----- [N/A] %s%n", args);
                System.out.println(e.getMessage());
                System.out.println();
            }
        }
        System.out.println();
    }

    public static String tryWith(String... lines) throws VMSupportException {
        try {
            List<String> commandString = getJavaInvokeLine();
            commandString.addAll(
                    Arrays.stream(lines)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList()));

            ProcessBuilder pb = new ProcessBuilder(commandString);
            Process p = pb.start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            InputStreamDrainer errDrainer = new InputStreamDrainer(p.getErrorStream(), baos);
            InputStreamDrainer outDrainer = new InputStreamDrainer(p.getInputStream(), baos);

            errDrainer.start();
            outDrainer.start();

            int ecode = p.waitFor();

            errDrainer.join();
            outDrainer.join();

            String msg = baos.toString();
            if (ecode == 0) {
                return msg;
            } else {
                throw new VMSupportException(msg);
            }
        } catch (IOException | InterruptedException ex) {
            throw new VMSupportException(ex.getMessage());
        }
    }

    public static List<String> getJavaInvokeLine() {
        List<String> command = new ArrayList<>();

        // jvm path
        command.add(getDefaultJvm());

        // jvm classpath
        command.add("-cp");
        if (isWindows()) {
            command.add('"' + System.getProperty("java.class.path") + '"');
        } else {
            command.add(System.getProperty("java.class.path"));
        }

        command.addAll(GLOBAL_JVM_FLAGS);

        return command;
    }

    private static String getDefaultJvm() {
        return System.getProperty("java.home") +
                File.separator +
                "bin" +
                File.separator +
                "java" +
                (isWindows() ? ".exe" : "");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    public static List<Config> getAvailableVMConfigs() {
        return AVAIL_JVM_CONFIGS;
    }

    private static int HOT_CPUS = -1;

    /**
     * Warm up the CPU schedulers, bring all the CPUs online to get the
     * reasonable estimate of the system capacity.
     *
     * @return online CPU count
     */
    public static int figureOutHotCPUs() {
        if (HOT_CPUS != -1) {
            return HOT_CPUS;
        }

        ExecutorService service = Executors.newCachedThreadPool();

        System.out.print("Burning up to figure out the exact CPU count...");

        int warmupTime = 1000;
        long lastChange = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<>();
        futures.add(service.submit(new BurningTask()));

        System.out.print(".");

        int max = 0;
        while (System.currentTimeMillis() - lastChange < warmupTime) {
            int cur = Runtime.getRuntime().availableProcessors();
            if (cur > max) {
                System.out.print(".");
                max = cur;
                lastChange = System.currentTimeMillis();
                futures.add(service.submit(new BurningTask()));
            }
        }

        for (Future<?> f : futures) {
            System.out.print(".");
            f.cancel(true);
        }

        service.shutdown();

        System.out.println(" done!");
        System.out.println();

        HOT_CPUS = max;

        return max;
    }

    static class BurningTask implements Runnable {

        @Override
        public void run() {
            while (!Thread.interrupted()) ; // burn;
        }
    }

    public static class Runtimes {
        static final int INT = 1 << 0;
        static final int C1 = 1 << 1;
        static final int C2 = 1 << 2;
        final int mod;

        private Runtimes(int mod) {
            this.mod = mod;
        }

        private static Runtimes none() {
            return new Runtimes(0);
        }

        private static Runtimes all() {
            return new Runtimes(INT | C1 | C2);
        }

        private static Runtimes intp() {
            return new Runtimes(INT);
        }

        private static Runtimes C1() {
            return new Runtimes(C1);
        }

        private static Runtimes C2() {
            return new Runtimes(C2);
        }

        private static Runtimes intpC1() {
            return new Runtimes(INT | C1);
        }

        private static Runtimes intpC2() {
            return new Runtimes(INT | C2);
        }

        public boolean hasC1() {
            return (mod & C1) != 0;
        }

        public boolean hasC2() {
            return (mod & C2) != 0;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;

            Runtimes runtimes = (Runtimes) o;
            return mod == runtimes.mod;
        }

        @Override
        public int hashCode() {
            return mod;
        }
    }

    public static class Config {
        private static final Random SEED_RANDOM = new Random();

        private final List<String> args;
        private final Runtimes availableRuntimes;
        private final Runtimes requiredRuntimes;
        private final boolean stress;
        private final boolean addStressSeed;

        private Config(List<String> args, Runtimes availableRuntimes, Runtimes requiredRuntimes, boolean stress) {
            this.args = args;
            this.availableRuntimes = availableRuntimes;
            this.requiredRuntimes = requiredRuntimes;
            this.stress = stress;
            this.addStressSeed = shouldAddStressSeed();
        }

        private boolean shouldAddStressSeed() {
            // Prefer to add the explicit stress seed to aid reproducibility.
            // Support the case where user already passes the seed, probably
            // for replication.
            if (stress && STRESS_SEED_AVAILABLE) {
                for (String arg : args) {
                    if (arg.contains("-XX:StressSeed")) return false;
                }
                return true;
            }
            return false;
        }

        public Runtimes availableRuntimes() {
            return availableRuntimes;
        }

        public Runtimes requiredRuntimes() {
            return requiredRuntimes;
        }

        public boolean stress() {
            return stress;
        }

        public List<String> origArgs() {
            return args;
        }

        public List<String> args() {
            if (addStressSeed) {
                List<String> na = new ArrayList<>(args);
                na.add("-XX:StressSeed=" + SEED_RANDOM.nextInt(Integer.MAX_VALUE));
                return na;
            } else {
                return args;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;

            Config config = (Config) o;
            return args.equals(config.args) &&
                    availableRuntimes.equals(config.availableRuntimes) &&
                    requiredRuntimes.equals(config.requiredRuntimes);
        }

        @Override
        public int hashCode() {
            int result = args.hashCode();
            result = 31 * result + availableRuntimes.hashCode();
            result = 31 * result + requiredRuntimes.hashCode();
            return result;
        }
    }

}
