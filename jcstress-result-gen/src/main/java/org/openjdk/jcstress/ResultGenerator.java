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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

public class ResultGenerator {
    private final Set<String> generatedResults = new HashSet<>();
    private final String srcRoot;

    public ResultGenerator(String srcRoot) {
        this.srcRoot = srcRoot;
    }

    public String generateResult(Class<?>... args) {
        boolean allPrimitive = true;
        String name = "";
        for (Class<?> k : args) {
            if (k.isPrimitive()) {
                if (k == boolean.class) name += "Z";
                if (k == byte.class)    name += "B";
                if (k == short.class)   name += "S";
                if (k == char.class)    name += "C";
                if (k == int.class)     name += "I";
                if (k == long.class)    name += "J";
                if (k == float.class)   name += "F";
                if (k == double.class)  name += "D";
            } else {
                name += "L";
                allPrimitive = false;
            }
        }
        name += "_Result";

        // already generated
        if (!generatedResults.add(name))
            return name;

        PrintWriter pw;
        try {
            Path dir = Paths.get(srcRoot, "org.openjdk.jcstress.infra.results.".split("\\."));
            Path file = dir.resolve(name + ".java");
            Files.createDirectories(dir);
            pw = new PrintWriter(file.toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        pw.println("package org.openjdk.jcstress.infra.results;");
        pw.println("");
        if (allPrimitive) {
            pw.println("import org.openjdk.jcstress.infra.Copyable;");
        } else {
            pw.println("import java.io.Serializable;");
        }
        pw.println("import org.openjdk.jcstress.annotations.Result;");
        pw.println("");
        pw.println("@Result");
        pw.println("public final class " + name + " implements " + (allPrimitive ? "Copyable" : "Serializable" ) + " {");

        {
            int n = 1;
            for (Class<?> k : args) {
                pw.println("    @sun.misc.Contended");
                pw.println("    @jdk.internal.vm.annotation.Contended");
                pw.println("    public " + k.getSimpleName() + " r" + n + ";");
                pw.println();
                n++;
            }

            pw.println("    @sun.misc.Contended");
            pw.println("    @jdk.internal.vm.annotation.Contended");
            pw.println("    public int jcstress_trap; // reserved for infrastructure use");
            pw.println();
        }

        // Hashcode generator optimized for the most frequent case of field values in {0,1}.

        pw.println("    public int hashCode() {");
        pw.println("        return ");
        {
            int n = 1;
            for (Class<?> k : args) {
                pw.print("        ");
                if (n != 1) {
                    pw.print(" + ");
                } else {
                    pw.print("   ");
                }
                if (k == boolean.class) {
                    pw.print("(r" + n + " ? 1 : 0)");
                } else
                if (k == byte.class || k == short.class || k == char.class || k == int.class) {
                    pw.print("r" + n);
                } else
                if (k == long.class || k == double.class || k == float.class) {
                    pw.print("(int) (r" + n + ")");
                } else
                {
                    pw.print("(r" + n + " == null ? 0 : r" + n + ".hashCode())");
                }

                if (n > 1) {
                    pw.println(" << " + (n - 1) + "");
                } else {
                    pw.println();
                }
                n++;
            }
        }
        pw.println("        ;");
        pw.println("    }");
        pw.println();
        pw.println("    public boolean equals(Object o) {");
        pw.println("        if (this == o) return true;");
        pw.println("        if (o == null || getClass() != o.getClass()) return false;");
        pw.println();
        pw.println("        " + name + " that = (" + name + ") o;");

        {
            int n = 1;
            for (Class<?> k : args) {
                if (k == boolean.class || k == byte.class || k == short.class || k == char.class
                        || k == int.class || k == long.class) {
                    pw.println("        if (r" + n + " != that.r" + n + ") return false;");
                } else if (k == double.class) {
                    pw.println("        if (Double.compare(r" + n + ", that.r" + n + ") != 0) return false;");
                } else if (k == float.class) {
                    pw.println("        if (Float.compare(r" + n + ", that.r" + n + ") != 0) return false;");
                } else {
                    pw.println("        if (!objEquals(r" + n + ", that.r" + n + ")) return false;");
                }
                n++;
            }
        }

        pw.println("        return true;");
        pw.println("    }");
        pw.println();

        pw.println("    public String toString() {");
        pw.print("        return \"\" + ");
        {
            int n = 1;
            for (Class<?> k : args) {
                if (n != 1)
                    pw.print(" + \", \" + ");
                pw.print("r" + n);
                n++;
            }
            pw.println(";");
        }
        pw.println("    }");
        pw.println();

        if (allPrimitive) {
            pw.println("    public Object copy() {");
            pw.println("        " + name + " copy = new " + name + "();");
            for (int n = 1; n <= args.length; n++) {
                pw.println("        copy.r" + n + " = r" + n + ";");
            }
            pw.println("        return copy;");
            pw.println("    }");
        }

        if (!allPrimitive) {
            pw.println("    private static boolean objEquals(Object a, Object b) {");
            pw.println("        return a == b || a != null && a.equals(b);");
            pw.println("    }");
        }

        pw.println("}");
        pw.close();

        return name;
    }
}
