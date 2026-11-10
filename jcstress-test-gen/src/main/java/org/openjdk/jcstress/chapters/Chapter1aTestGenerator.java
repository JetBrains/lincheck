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
import java.nio.ByteOrder;
import java.util.*;

public class Chapter1aTestGenerator {

    public static final String PREFIX = "org.openjdk.jcstress.tests";
    public static final String[] TYPES_ALL = new String[]{"byte", "boolean", "char", "short", "int", "float", "long", "double", "String"};
    public static final String[] TYPES_VIEWS = new String[]{"char", "short", "int", "float", "long", "double"};

    public static void main(String... args) throws IOException {
        if (args.length < 1) {
            throw new IllegalStateException("Need a destination argument");
        }
        String dest = args[0];

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-VarHandleFieldAtomicityTest.java.template"),
                "accessAtomic.varHandles.fields"
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-VarHandleArrayAtomicityTest.java.template"),
                "accessAtomic.varHandles.arrays"
        );

        makeBufferTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-VarHandleByteArrayViewAtomicityTest.java.template"),
                "accessAtomic.varHandles.byteArray"
        );

        makeBufferTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-VarHandleHeapByteBufferViewAtomicityTest.java.template"),
                "accessAtomic.varHandles.byteBuffer.heap"
        );

        makeBufferTests(
                dest,
                GeneratorUtils.readFromResource("/accessAtomic/X-VarHandleDirectByteBufferViewAtomicityTest.java.template"),
                "accessAtomic.varHandles.byteBuffer.direct"
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/coherence/X-VarHandleFieldCoherenceTest.java.template"),
                "coherence.varHandles.fields"
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/coherence/X-VarHandleArrayCoherenceTest.java.template"),
                "coherence.varHandles.arrays"
        );

        makeBufferTests(
                dest,
                GeneratorUtils.readFromResource("/coherence/X-VarHandleByteArrayViewCoherenceTest.java.template"),
                "coherence.varHandles.byteArray"
        );

        makeBufferTests(
                dest,
                GeneratorUtils.readFromResource("/coherence/X-VarHandleHeapByteBufferViewCoherenceTest.java.template"),
                "coherence.varHandles.byteBuffer.heap"
        );

        makeBufferTests(
                dest,
                GeneratorUtils.readFromResource("/coherence/X-VarHandleDirectByteBufferViewCoherenceTest.java.template"),
                "coherence.varHandles.byteBuffer.direct"
        );
    }

    private enum VarHandleMode {
        OPAQUE("opaque"),
        ACQ_REL("acqrel"),
        VOLATILE("volatiles"),
        ;

        private String label;

        VarHandleMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static void makeTests(String dest, String template, String label) throws IOException {
        for (VarHandleMode gs : VarHandleMode.values()) {
            String pack = PREFIX + "." + label + "." + gs;
            for (String type : TYPES_ALL) {
                String name = testName(type);
                String res = Spp.spp(template,
                        keys(type, gs),
                        vars(type, pack, name, gs, null));

                GeneratorUtils.writeOut(dest, pack, name, res);
            }
        }
    }

    private static void makeBufferTests(String dest, String template, String label) throws IOException {
        for (VarHandleMode gs : VarHandleMode.values()) {
            for (ByteOrder bo : new ByteOrder[] { ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN }) {
                String pack = PREFIX + "." + label + "." + bo.toString().toLowerCase().replace("_endian", "") + "." + gs;
                for (String type : TYPES_VIEWS) {
                    String name = testName(type);
                    String res = Spp.spp(template,
                            keys(type, gs),
                            vars(type, pack, name, gs, bo));

                    GeneratorUtils.writeOut(dest, pack, name, res);
                }
            }
        }
    }

    private static Map<String, String> vars(String type, String pack, String name, VarHandleMode mode, ByteOrder bo) {
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
        map.put("package", pack);
        map.put("byteOrder", String.valueOf(bo));

        switch (mode) {
            case OPAQUE: {
                map.put("setOp", "setOpaque");
                map.put("getOp", "getOpaque");
                break;
            }
            case ACQ_REL: {
                map.put("setOp", "setRelease");
                map.put("getOp", "getAcquire");
                break;
            }
            case VOLATILE: {
                map.put("setOp", "setVolatile");
                map.put("getOp", "getVolatile");
                break;
            }
            default:
                throw new IllegalStateException("" + mode);
        }
        return map;
    }

    private static Set<String> keys(String type, VarHandleMode mode) {
        Set<String> set = new HashSet<>();
        set.add(type);
        return set;
    }

    private static String testName(String type) {
        return StringUtils.upcaseFirst(type) + "Test";
    }

}
