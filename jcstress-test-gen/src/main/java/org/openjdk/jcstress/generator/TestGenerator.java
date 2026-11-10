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
package org.openjdk.jcstress.generator;

import org.openjdk.jcstress.util.ResultUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class TestGenerator {

    private final String srcRoot;

    public TestGenerator(String srcRoot) {
        this.srcRoot = srcRoot;
    }

    public void run() throws IOException {
        generateMemoryEffects();
    }

    public void generateMemoryEffects() throws IOException {
        for (Class<?> varType : Types.VAR_PRIMITIVE_TYPES) {
            for (Class<?> guardType : Types.GUARD_PRIMITIVE_TYPES) {
                generate(new Types(guardType, varType), new VolatileReadWrite(guardType), "volatile_" + guardType + "_" + varType, "org.openjdk.jcstress.tests.memeffects.basic.volatiles");
            }
            generate(new Types(int.class, varType), new SynchronizedBlock(), "lock_" + varType, "org.openjdk.jcstress.tests.memeffects.basic.lock");

            for (Class<?> guardType : Types.SUPPORTED_ATOMICS) {
                Class<?> primType = Types.mapAtomicToPrim(guardType);
                for (AcqType acqType : AcqType.values()) {
                    for (RelType relType : RelType.values()) {
                        try {
                            generate(
                                new Types(primType, varType),
                                new Atomic_X(guardType, primType, acqType, relType),
                                "atomic_" + acqType + "_" + relType + "_" + varType,
                                "org.openjdk.jcstress.tests.memeffects.basic.atomic." + guardType.getSimpleName());
                        } catch (IllegalArgumentException iae) {
                            // not compatible acq/rel types, move on.
                        }
                    }
                }
            }

            for (Class<?> guardType : Types.SUPPORTED_ATOMIC_UPDATERS) {
                Class<?> primType = Types.mapAtomicToPrim(guardType);
                for (AcqType acqType : AcqType.values()) {
                    for (RelType relType : RelType.values()) {
                        try {
                            generate(
                                new Types(primType, varType),
                                new Atomic_Updater_X(guardType, primType, acqType, relType),
                                "atomic_" + acqType + "_" + relType + "_" + varType,
                                "org.openjdk.jcstress.tests.memeffects.basic.atomicupdaters." + guardType.getSimpleName());
                        } catch (IllegalArgumentException iae) {
                            // not compatible acq/rel types, move on.
                        }
                    }
                }
            }

        }
    }

   public void generate(Types types, Primitive prim, String klass, String pkg) throws IOException {
        String resultName = ResultUtils.resultName(types.all());

        Path dir = Paths.get(srcRoot, pkg.split("\\."));
        Path file = dir.resolve(klass + ".java");
        Files.createDirectories(dir);
        PrintWriter pw = new PrintWriter(file.toFile());

        pw.println("package " + pkg +";");
        pw.println();
        if (prim.getClassName() != null) {
            pw.println("import " + prim.getClassName() + ";");
        }
        pw.println("import org.openjdk.jcstress.infra.results." + resultName + ";");
        pw.println("import org.openjdk.jcstress.annotations.Actor;");
        pw.println("import org.openjdk.jcstress.annotations.JCStressTest;");
        pw.println("import org.openjdk.jcstress.annotations.State;");
        pw.println("import org.openjdk.jcstress.annotations.Outcome;");
        pw.println("import org.openjdk.jcstress.annotations.Expect;");
        pw.println();
        pw.println("@JCStressTest");
        pw.println("@Outcome(id = \"" + getDefaultToString(types.type(0)) +", " + getDefaultToString(types.type(1)) + "\", expect = Expect.ACCEPTABLE, desc = \"Seeing default guard, can see any value\")");
        pw.println("@Outcome(id = \"" + getDefaultToString(types.type(0)) +", " + getSetValue(types.type(1)) + "\", expect = Expect.ACCEPTABLE, desc = \"Seeing default guard, can see any value\")");
        pw.println("@Outcome(id = \"" + getSetValue(types.type(0)) +", " + getSetValue(types.type(1)) + "\", expect = Expect.ACCEPTABLE, desc = \"Seeing set guard, seeing the updated value\")");
        pw.println("@Outcome(id = \"" + getSetValue(types.type(0)) +", " + getDefaultToString(types.type(1)) + "\", expect = Expect.FORBIDDEN, desc = \"Seeing set guard, not seeing the updated value\")");
        pw.println("@State");
        pw.println("public class " + klass + " {");
        pw.println();
        pw.println("    " + prim.printStateField(klass));
        pw.println("    public " + types.type(1) + " a;");
        pw.println();
        pw.println("    @Actor");
        pw.println("    public void actor1() {");
        pw.println("        " + prim.printRelease("        a = " + getRValue(types.type(1)) +";"));
        pw.println("    }");
        pw.println();
        pw.println("    @Actor");
        pw.println("    public void actor2(" + resultName +" r) {");
        pw.println("        " + prim.printAcquire("        r.r2 = a;"));
        pw.println("    }");
        pw.println();
        pw.println("}");

        pw.close();
    }

    public static String getDefaultValue(Class<?> k) {
        if (k == boolean.class) return "false";
        if (k == byte.class)    return "0";
        if (k == short.class)   return "0";
        if (k == char.class)    return "0";
        if (k == int.class)     return "0";
        if (k == long.class)    return "0";
        if (k == float.class)   return "0.0";
        if (k == double.class)  return "0.0";
        return null;
    }

    public static String getDefaultToString(Class<?> k) {
        if (k == boolean.class) return "false";
        if (k == byte.class)    return "0";
        if (k == short.class)   return "0";
        if (k == char.class)    return "\u0000";
        if (k == int.class)     return "0";
        if (k == long.class)    return "0";
        if (k == float.class)   return "0.0";
        if (k == double.class)  return "0.0";
        return null;
    }

    public static String getSetValue(Class<?> k) {
        if (k == boolean.class) return "true";
        if (k == byte.class)    return "1";
        if (k == short.class)   return "42";
        if (k == char.class)    return "A";
        if (k == int.class)     return "42";
        if (k == long.class)    return "42";
        if (k == float.class)   return "42.0";
        if (k == double.class)  return "42.0";
        return null;
    }

    public static String getRValue(Class<?> k) {
        if (k == boolean.class) return "true";
        if (k == byte.class)    return "(byte)1";
        if (k == short.class)   return "42";
        if (k == char.class)    return "'A'";
        if (k == int.class)     return "42";
        if (k == long.class)    return "42L";
        if (k == float.class)   return "42.0f";
        if (k == double.class)  return "42.0d";
        return null;
    }

    public static String getUnitValue(Class<?> k) {
        if (k == boolean.class) return "false";
        if (k == byte.class)    return "1";
        if (k == short.class)   return "1";
        if (k == char.class)    return "(char)1";
        if (k == int.class)     return "1";
        if (k == long.class)    return "1L";
        if (k == float.class)   return "1.0f";
        if (k == double.class)  return "1.0d";
        return null;
    }

    public static class Types {
        public static final Class<?>[] GUARD_PRIMITIVE_TYPES =
                new Class<?>[] { boolean.class, byte.class, short.class, char.class,
                                 int.class, long.class, float.class, double.class };

        // At least one per data width, plus both shapes of floating point
        public static final Class<?>[] VAR_PRIMITIVE_TYPES =
                new Class<?>[] { byte.class, short.class, int.class, long.class, float.class, double.class };

        public static final Class<?>[] SUPPORTED_ATOMICS =
                new Class<?>[] { AtomicInteger.class, AtomicLong.class, AtomicBoolean.class };

        public static final Class<?>[] SUPPORTED_ATOMIC_UPDATERS =
                new Class<?>[] { AtomicIntegerFieldUpdater.class, AtomicLongFieldUpdater.class };

        private final Class<?>[] types;

        public Types(Class<?>... types) {
            this.types = types;
        }

        public Class<?> type(int index) {
            return types[index];
        }

        public Class<?>[] all() {
            return types;
        }

        public static Class<?> mapAtomicToPrim(Class<?> guardType) {
            if (guardType == AtomicInteger.class) return int.class;
            if (guardType == AtomicLong.class) return long.class;
            if (guardType == AtomicBoolean.class) return boolean.class;
            if (guardType == AtomicIntegerFieldUpdater.class) return int.class;
            if (guardType == AtomicLongFieldUpdater.class) return long.class;
            throw new IllegalStateException("No case");
        }
    }

}
