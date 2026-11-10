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
package org.openjdk.jcstress.chapters;

import org.openjdk.jcstress.Spp;
import org.openjdk.jcstress.Values;
import org.openjdk.jcstress.util.StringUtils;

import java.io.*;
import java.util.*;

public class Chapter0aTestGenerator {

    public static final String PREFIX = "org.openjdk.jcstress.tests";
    public static final String[] TYPES = new String[]{"byte", "boolean", "char", "short", "int", "float", "long", "double", "String"};

    public static void main(String... args) throws IOException {
        if (args.length < 1) {
            throw new IllegalStateException("Need a destination argument");
        }
        String dest = args[0];

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-FieldAtomicityTest.java.template"),
                "accessAtomic.fields",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-FieldConflictAtomicityTest.java.template"),
                "accessAtomic.fields.conflict",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/defaultValues/X-FieldDefaultValuesTest.java.template"),
                "defaultValues.fields",
                new String[]{ "", "volatile", "final", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/init/X-FieldInitTest.java.template"),
                "init.fields",
                new String[]{ "", "volatile", "final", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/init/X-FieldInitClassTest.java.template"),
                "initClass.fields",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/tearing/X-FieldTearingTest.java.template"),
                "tearing.fields",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/defaultValues/X-ArrayDefaultValuesTest.java.template"),
                "defaultValues.arrays.small",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/defaultValues/X-ArrayLargeDefaultValuesTest.java.template"),
                "defaultValues.arrays.large",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/init/X-ArrayInitTest.java.template"),
                "init.arrays.small",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/init/X-ArrayLargeInitTest.java.template"),
                "init.arrays.large",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/init/X-ArrayInitClassTest.java.template"),
                "initClass.arrays.small",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/init/X-ArrayLargeInitClassTest.java.template"),
                "initClass.arrays.large",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/init/X-ArrayInitLengthTest.java.template"),
                "initLen.arrays.small",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/init/X-ArrayLargeInitLengthTest.java.template"),
                "initLen.arrays.large",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-ArrayAtomicityTest.java.template"),
                "accessAtomic.arrays",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-ArrayCopySrcAtomicityTest.java.template"),
                "accessAtomic.arraycopy.src",
                new String[]{ "" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-ArrayCopyDstAtomicityTest.java.template"),
                "accessAtomic.arraycopy.dst",
                new String[]{ "" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-ArrayCopyConflictAtomicityTest.java.template"),
                "accessAtomic.arraycopy.conflict",
                new String[]{ "" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/tearing/X-ArrayTearingTest.java.template"),
                "tearing.arrays.small",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/tearing/X-ArrayLargeTearingTest.java.template"),
                "tearing.arrays.large",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/coherence/X-FieldCoherenceTest.java.template"),
                "coherence.fields",
                new String[]{ "", "volatile", "sync" }
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/coherence/X-ArrayCoherenceTest.java.template"),
                "coherence.arrays",
                new String[]{ "", "volatile", "sync" }
        );
    }

    private static void makeTests(String dest, String template, String label, String[] modifiers) throws IOException {
        for (String modifier : modifiers) {
            String pack = PREFIX + "." + label + "." + packageModifier(modifier);
            for (String type : TYPES) {
                if (!alwaysAtomic(modifier, type, label)) continue;
                if (!coherent(modifier, type, label)) continue;

                String name = testName(type);
                String res = Spp.spp(template,
                        keys(modifier, type, label),
                        vars(modifier, type, pack, name));

                GeneratorUtils.writeOut(dest, pack, name, res);
            }
        }
    }

    private static String packageModifier(String modifier) {
        switch (modifier) {
            case "":
                return "plain";
            case "sync":
                return "sync";
            case "volatile":
            case "final":
                return modifier + "s";
            default:
                throw new IllegalArgumentException("Unknown modifier: " + modifier);
        }
    }

    private static String fieldModifier(String modifier) {
        switch (modifier) {
            case "":
            case "sync":
                return "";
            case "volatile":
            case "final":
                return modifier + " ";
            default:
                throw new IllegalArgumentException("Unknown modifier: " + modifier);
        }
    }

    private static Map<String, String> vars(String modifier, String type, String pack, String name) {
        Map<String, String> map = new HashMap<>();
        map.put("type", type);
        map.put("TYPE", type.toUpperCase());
        map.put("Type", StringUtils.upcaseFirst(type));
        map.put("T", GeneratorUtils.toDescriptor(type));
        map.put("name", name);
        map.put("default", Values.DEFAULTS.get(type));
        map.put("defaultLiteral", Values.DEFAULTS_LITERAL.get(type));
        map.put("set", Values.VALUES.get(type));
        map.put("setLiteral", Values.VALUES_LITERAL.get(type));
        map.put("modifier", fieldModifier(modifier));
        map.put("package", pack);
        return map;
    }

    private static Set<String> keys(String modifier, String type, String label) {
        Set<String> set = new HashSet<>();
        set.add(type);
        set.add(modifier);
        return set;
    }

    private static boolean alwaysAtomic(String modifier, String type, String label) {
        return (modifier.equals("volatile") && !label.contains("array")) ||
                !(type.equals("double") || type.equals("long")) ||
                modifier.equals("sync");
    }

    private static boolean coherent(String modifier, String type, String label) {
        return (modifier.equals("volatile") && !label.contains("array")) ||
                modifier.equals("sync");
    }

    private static String testName(String type) {
        return StringUtils.upcaseFirst(type) + "Test";
    }

}
