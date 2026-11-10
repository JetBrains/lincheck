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
package org.openjdk.jcstress.generator.seqcst;

import org.openjdk.jcstress.util.ResultUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class SeqCstTraceGenerator {

    private final String srcDir;
    private final String pkg;
    private final Target target;

    public static void generate(String dst, String pkg, Target target) {
        new SeqCstTraceGenerator(dst, pkg, target).generate();
    }

    private SeqCstTraceGenerator(String dst, String pkg, Target target) {
        this.srcDir = dst;
        this.pkg = pkg;
        this.target = target;
    }

    public void generate() {
        /*
           Step 1. Distribute load-stores between threads, yielding all possible scenarios.
         */
        List<MultiThread> multiThreads = new ArrayList<>();

        // (ops, variables, threads)
        int[][] triplets = {
                {2, 1, 2},
                {3, 1, 2},
                {4, 1, 2},
                {4, 2, 2},

                {4, 2, 3},
                {5, 1, 3},
                {5, 2, 3},
                {6, 3, 3},

                {6, 1, 4},
                {6, 2, 4},
        };

        for (int[] tri : triplets) {
            List<MultiThread> p = new Phase(tri[0], tri[1], tri[2]).run();
            multiThreads.addAll(p);
        }

        System.out.println(multiThreads.size() + " interesting testcases");

        /*
            Step 2. Figure out what executions are sequentially consistent (needed for grading!),
            and emit the tests.
         */

        Set<String> generatedIds = new HashSet<>();

        System.out.print("Figuring out SC outcomes for the testcases: ");
        int testCount = 0;
        for (MultiThread mt : multiThreads) {
            Set<TraceResult> scResults = new HashSet<>();

            // Compute all SC results from the linearization of MT
            for (Trace linear : mt.linearize()) {
                TraceResult results = linear.interpret();
                scResults.add(results);
            }

            Set<TraceResult> allResults = mt.racyResults();

            if (!allResults.containsAll(scResults)) {
                System.out.println(mt.canonicalId());
                System.out.println(scResults);
                System.out.println(allResults);
                throw new IllegalStateException("SC results should be subset of all results");
            }

            if (scResults.equals(allResults)) {
                // all racy results are indistinguishable from SC
                //    => the violations are undetectable
                // nothing to do here,
                continue;
            }

            generatedIds.add(mt.canonicalId());

            emit(mt, scResults);
            if ((testCount++ % 100) == 0)
                System.out.print(".");
        }
        System.out.println();
        System.out.println("Generated " + testCount + " interesting testcases");

        /*
            Step 3. Check that no important cases were filtered.

            The nomenclature is derived from Maranget, Sarkar, Sewell,
              "A Tutorial Introduction to the ARM and POWER Relaxed Memory Models"
         */
        check(generatedIds, "L1_L2__S2_S1", "MP");

        check(generatedIds, "L1_S2__S2_S1", "S");
        check(generatedIds, "S1_L2__S2_S1", "R");
        check(generatedIds, "S1_S2__S2_S1", "2+2W");
        check(generatedIds, "L1_S2__S1__S2_L1", "WRW+WR");
        check(generatedIds, "L1_S2__S1__S2_S1", "WRR+2W");

        check(generatedIds, "S1_L2__S2_L1", "SB");
        check(generatedIds, "L1_S2__L2_S1", "LB");

        check(generatedIds, "L1_L2__L2_S1__S2", "WRC");
        check(generatedIds, "L1_S2__L2_S1__S1", "WWC");
        check(generatedIds, "L1_L2__S1__S2_L1", "RWC");
        check(generatedIds, "L1_L2__S1__S2_S1", "WRR+2W");

        check(generatedIds, "L1_L2__S2_S1", "PPO");
        check(generatedIds, "L1_L2__L2_L1__S1__S2", "IRIW");
        check(generatedIds, "L1_L2__L2_S1__S1__S2", "IRRWIW");
        check(generatedIds, "L1_S2__L2_S1__S1__S2", "IRWIW");

        check(generatedIds, "L1_L1__S1_S1", "CoRR0");
        check(generatedIds, "L1_L1__S1", "CoRR1");
        check(generatedIds, "L1_L1__L1_L1__S1__S1", "CoRR2");
        check(generatedIds, "L1_S1__S1", "CoRW");
        check(generatedIds, "S1__S1_L1", "CoWR");

        check(generatedIds, "L1_L2__L3_S1__S2_S3", "ISA2");
        check(generatedIds, "L1_S2__L2_S3__L3_S1", "3.LB");
        check(generatedIds, "S1_L2__S2_L3__S3_L1", "3.SB");

        check(generatedIds, "L1_L2__S2_L3__S3_S1", "W+RWC");

        check(generatedIds, "L1_S2__S2_L3__S3_S1", "Z6.0");
        check(generatedIds, "L1_S2__S2_S3__S3_S1", "Z6.1");
        check(generatedIds, "L1_S2__L2_S3__S3_S1", "Z6.2");
        check(generatedIds, "L1_L2__S2_S3__S3_S1", "Z6.3");
        check(generatedIds, "S1_L2__S2_L3__S3_S1", "Z6.4");
        check(generatedIds, "S1_L2__S2_S3__S3_S1", "Z6.5");
        check(generatedIds, "S1_S2__S2_S3__S3_S1", "3.2W");
    }

    private void check(Set<String> ids, String id, String info) {
        if (!ids.contains(id)) {
            throw new IllegalStateException("Generated cases should contain " + info);
        }
    }

    private void emit(MultiThread mt, Collection<TraceResult> scResults) {
        String klass = mt.canonicalId() + "_Test";

        Class<?>[] klasses = new Class<?>[mt.loadCount() + mt.allVariables().size()];
        switch (target) {
            case SYNCHRONIZED:
            case VOLATILE_PRIM:
                Arrays.fill(klasses, int.class);
                break;
            case VOLATILE_REF:
                Arrays.fill(klasses, Object.class);
                break;
            default:
                throw new IllegalStateException("Unknown target: " + target);
        }

        String resultName = ResultUtils.resultName(klasses);

        PrintWriter pw;
        try {
            Path dir = Paths.get(srcDir, pkg.split("\\."));
            Path file = dir.resolve(klass + ".java");
            Files.createDirectories(dir);
            pw = new PrintWriter(file.toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        int threads = mt.threads().size();

        pw.println("package " + pkg + ";");
        pw.println();
        pw.println("import org.openjdk.jcstress.infra.results.*;");
        pw.println("import org.openjdk.jcstress.annotations.*;");
        pw.println();
        pw.println("@JCStressTest");
        pw.println("@Outcome(id = {");

        for (TraceResult r : scResults) {
            List<String> mappedValues = new ArrayList<>();
            for (Value v : r.getResults().values()) {
                mappedValues.add(convertValue(v));
            }
            for (Value v : r.getVars().values()) {
                mappedValues.add(convertValue(v));
            }
            pw.println("            \"" + mappedValues.stream().collect(Collectors.joining(", ")) + "\",");
        }
        pw.println("}, expect = Expect.ACCEPTABLE, desc = \"Sequential consistency.\")");

        pw.println("@State");
        pw.println("public class " + klass + " {");
        pw.println();

        Set<Integer> exist = new HashSet<>();
        for (Trace trace : mt.threads())  {
            for (Op op : trace.ops()) {
                if (exist.add(op.getVarId())) {
                    switch (target) {
                        case VOLATILE_PRIM:
                            pw.println("    volatile int x" + op.getVarId() + ";");
                            break;
                        case VOLATILE_REF:
                            pw.println("    volatile String x" + op.getVarId() + ";");
                            break;
                        case SYNCHRONIZED:
                            pw.println("    int x" + op.getVarId() + ";");
                            break;
                        default:
                            throw new IllegalStateException("" + target);
                    }
                }
            }
        }
        pw.println();

        for (int t = 0; t < threads; t++) {
            Trace trace = mt.threads().get(t);

            pw.println("    @Actor");
            String sig = trace.hasLoads() ? "(" + resultName + " r)" : "()";
            pw.println("    public void actor" + (t + 1) + sig + " {");

            for (Op op : trace.ops()) {
                switch (op.getType()) {
                    case LOAD:
                        if (target == Target.SYNCHRONIZED) {
                            pw.println("        synchronized (this) {");
                            pw.print("    ");
                        }
                        pw.println("        r.r" + op.getResult() + " = x" + op.getVarId() + ";");
                        if (target == Target.SYNCHRONIZED) {
                            pw.println("        }");
                        }
                        break;
                    case STORE:
                        if (target == Target.SYNCHRONIZED) {
                            pw.println("        synchronized (this) {");
                            pw.print("    ");
                        }
                        String quotes = (target == Target.VOLATILE_REF) ? "\"" : "";
                        pw.println("        x" + op.getVarId() + " = " + quotes + op.getValue() + quotes + ";");
                        if (target == Target.SYNCHRONIZED) {
                            pw.println("        }");
                        }
                        break;
                }
            }

            pw.println("    }");
            pw.println();
        }

        pw.println("    @Arbiter");
        pw.println("    public void arbiter(" + resultName + " r) {");
        int idx = mt.loadCount() + 1;
        if (target == Target.SYNCHRONIZED) {
            pw.println("        synchronized (this) {");
            pw.print("    ");
        }
        for (Integer varId : mt.allVariables()) {
            pw.println("        r.r" + idx + " = x" + varId + ";");
            idx++;
        }
        if (target == Target.SYNCHRONIZED) {
            pw.println("        }");
        }
        pw.println("    }");
        pw.println();

        pw.println("}");

        pw.close();
    }

    private String convertValue(Value v) {
        switch (target) {
            case SYNCHRONIZED:
            case VOLATILE_PRIM:
                return v.toString();
            case VOLATILE_REF:
                return v.equals(Value.defaultOne()) ? "null" : v.toString();
            default:
                throw new IllegalStateException("Unknown target: " + target);
        }
    }

    private List<Trace> product(List<Trace> traces, List<Op> ops) {
        List<Trace> newTraces = new ArrayList<>();
        for (Trace trace : traces) {
            for (Op op : ops) {
                newTraces.add(trace.pushTail(op));
            }
        }
        return newTraces;
    }

}
