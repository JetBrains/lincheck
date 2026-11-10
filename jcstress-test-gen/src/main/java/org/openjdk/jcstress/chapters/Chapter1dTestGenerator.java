/*
 * Copyright (c) 2017, Red Hat Inc. All rights reserved.
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

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Chapter1dTestGenerator {

    public static final String PREFIX = "org.openjdk.jcstress.tests";

    public static final String[] TYPES_G = new String[]{"byte", "boolean", "char", "short", "int", "float", "long", "double", "String"};

    // Test makes little sense with non-access-atomic types. Access atomicity is verified elsewhere.
    public static final String[] TYPES_V = new String[]{"byte", "boolean", "char", "short", "int", "float", "String"};

    public static final String[] TYPES_VIEWS = new String[]{"char", "short", "int", "float", "long", "double"};

    public static void main(String... args) throws IOException {
        if (args.length < 1) {
            throw new IllegalStateException("Need a destination argument");
        }
        String dest = args[0];

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/acqrel/X-VarHandleFieldAcqRelTest.java.template"),
                "acqrel.varHandles.fields"
        );

        makeTests(
                dest,
                GeneratorUtils.readFromResource("/acqrel/X-VarHandleArrayAcqRelTest.java.template"),
                "acqrel.varHandles.arrays"
        );

        makeBufferTests(
                dest,
                GeneratorUtils.readFromResource("/acqrel/X-VarHandleByteArrayViewAcqRelTest.java.template"),
                "acqrel.varHandles.byteArray"
        );

        makeBufferTests(
                dest,
                GeneratorUtils.readFromResource("/acqrel/X-VarHandleHeapByteBufferViewAcqRelTest.java.template"),
                "acqrel.varHandles.byteBuffer.heap"
        );

        makeBufferTests(
                dest,
                GeneratorUtils.readFromResource("/acqrel/X-VarHandleDirectByteBufferViewAcqRelTest.java.template"),
                "acqrel.varHandles.byteBuffer.direct"
        );
    }

    private enum VarHandleMode {
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
        // Only care about modes where acq/rel is expected
        for (VarHandleMode gs : new VarHandleMode[] { VarHandleMode.ACQ_REL, VarHandleMode.VOLATILE } ) {
            String pack = PREFIX + "." + label + "." + gs;
            for (String typeG : TYPES_G) {
                for (String typeV : TYPES_V) {
                    String name = testName(typeG, typeV);
                    String res = Spp.spp(template,
                            keys(typeG, typeV, gs),
                            vars(typeG, typeV, pack, name, gs, null));

                    GeneratorUtils.writeOut(dest, pack, name, res);
                }
            }
        }
    }

    private static void makeBufferTests(String dest, String template, String label) throws IOException {
        // Only care about modes where acq/rel is expected
        for (VarHandleMode gs : new VarHandleMode[] { VarHandleMode.ACQ_REL, VarHandleMode.VOLATILE } ) {
            for (ByteOrder bo : new ByteOrder[] { ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN }) {
                String pack = PREFIX + "." + label + "." + bo.toString().toLowerCase().replace("_endian", "") + "." + gs;
                for (String typeG : TYPES_VIEWS) {
                    for (String typeV : TYPES_V) {
                        String name = testName(typeG, typeV);
                        String res = Spp.spp(template,
                                keys(typeG, typeV, gs),
                                vars(typeG, typeV, pack, name, gs, bo));

                        GeneratorUtils.writeOut(dest, pack, name, res);
                    }
                }
            }
        }
    }

    private static Map<String, String> vars(String typeG, String typeV, String pack, String name, VarHandleMode mode, ByteOrder bo) {
        Map<String, String> map = new HashMap<>();

        map.put("typeG", typeG);
        map.put("typeV", typeV);
        map.put("TYPEG", typeG.toUpperCase());
        map.put("TYPEV", typeV.toUpperCase());
        map.put("TypeG", StringUtils.upcaseFirst(typeG));
        map.put("TypeV", StringUtils.upcaseFirst(typeV));
        map.put("TG", GeneratorUtils.toDescriptor(typeG));
        map.put("TV", GeneratorUtils.toDescriptor(typeV));
        map.put("name", name);
        map.put("defaultG", Values.DEFAULTS.get(typeG));
        map.put("defaultV", Values.DEFAULTS.get(typeV));
        map.put("defaultGLiteral", Values.DEFAULTS_LITERAL.get(typeG));
        map.put("defaultVLiteral", Values.DEFAULTS_LITERAL.get(typeV));
        map.put("setG", Values.VALUES.get(typeG));
        map.put("setV", Values.VALUES.get(typeV));
        map.put("setGLiteral", Values.VALUES_LITERAL.get(typeG));
        map.put("setVLiteral", Values.VALUES_LITERAL.get(typeV));

        map.put("package", pack);
        map.put("byteOrder", String.valueOf(bo));

        switch (mode) {
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

    private static Set<String> keys(String modifier, String type, VarHandleMode mode) {
        Set<String> set = new HashSet<>();
        set.add(type);
        set.add(modifier);
        return set;
    }

    private static String testName(String typeG, String typeV) {
        return StringUtils.upcaseFirst(typeG) + StringUtils.upcaseFirst(typeV) + "Test";
    }
}
