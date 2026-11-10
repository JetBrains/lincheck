/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static java.util.Set.of;
import static org.openjdk.jcstress.chapters.Chapter1cTestGenerator.Method.*;
import static org.openjdk.jcstress.chapters.Chapter1cTestGenerator.Target.Operation.*;
import static org.openjdk.jcstress.chapters.Chapter1cTestGenerator.Target.*;
import static org.openjdk.jcstress.chapters.GeneratorUtils.*;

public class Chapter1cTestGenerator {

    private static final String BASE_PKG = "org.openjdk.jcstress.tests.fences.varHandles";
    private static final String[] TYPES = new String[]{"byte", "boolean", "char", "short", "int", "float", "long", "double", "String"};

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalStateException("Need a destination argument");
        }
        String dest = args[0];

        for(Target target : Target.values()) {
            final String templateName = target.template;
            makeFieldTests(dest, target, templateName,
                    readFromResource("/fences/" + templateName + ".java.template"));
        }
    }

    private static void makeFieldTests(String dest, Target target, String templateName, String template) throws IOException {
        for (String type : TYPES) {
            if (!alwaysAtomic(type)) continue;

            for (Operation operation : target.operations) {
                String result = template.replaceAll(target.target, operation.operation);

                String pkg = BASE_PKG + "." + templateName.replaceAll("^X-", "");
                String testName = operation.method.name() + upcaseFirst(type);

                writeOut(dest, pkg, testName, Spp.spp(result,
                        keys(type),
                        vars(type, "this", pkg, testName)
                ));
            }
        }
    }

    private static boolean alwaysAtomic(String type) {
        return !(type.equals("double") || type.equals("long"));
    }

    private static Set<String> keys(String type) {
        return of();
    }

    private static Map<String, String> vars(String type, String object, String pkg, String testName) {
        Map<String, String> map = new HashMap<>();
        map.put("type", type);
        map.put("Type", upcaseFirst(type));
        map.put("T", GeneratorUtils.toDescriptor(type));
        map.put("TestClassName", testName);
        map.put("package", pkg);
        map.put("object", object);

        map.put("value0", Values.DEFAULTS.get(type));
        map.put("value1", Values.VALUES.get(type));
        map.put("valueLiteral0", Values.DEFAULTS_LITERAL.get(type));
        map.put("valueLiteral1", Values.VALUES_LITERAL.get(type));
        return map;
    }

    enum Method {
        FullFence,
        AcquireFence,
        ReleaseFence,
        LoadLoadFence,
        StoreStoreFence,
    }

    private static final String LNSEP = System.getProperty("line.separator");

    enum Target {
        T_GET_LOADLOADFENCE(
                "X-LoadLoadFenceTest",
                "%GetLoadLoadFence%",
                of(GET_ACQUIREFENCE, GET_LOADLOADFENCE, GET_FULLFENCE)
        ),

        T_LOADSTOREFENCE_SET(
                "X-LoadStoreFenceTest1",
                "%LoadStoreFenceSet%",
                of(RELEASEFENCE_SET, FULLFENCE_SET)
        ),

        T_GET_LOADSTOREFENCE(
                "X-LoadStoreFenceTest2",
                "%GetLoadStoreFence%",
                of(GET_ACQUIREFENCE, GET_FULLFENCE)
        ),

        T_STORESTOREFENCE_SET(
                "X-StoreStoreFenceTest1",
                "%StoreStoreFenceSet%",
                of(RELEASEFENCE_SET, STORESTOREFENCE_SET, FULLFENCE_SET)
        ),

        T_SET_STORESTOREFENCE(
                "X-StoreStoreFenceTest2",
                "%SetStoreStoreFence%",
                of(SET_RELEASEFENCE, SET_STORESTOREFENCE, SET_FULLFENCE)
        ),

        T_SET_STORELOADFENCE(
                "X-StoreLoadFenceTest",
                "%SetStoreLoadFence%",
                of(SET_FULLFENCE)
        ),

        ;

        Target(String template, String target, Set<Operation> operations) {
            this.template = template;
            this.target = target;
            this.operations = operations;
        }

        String template;
        String target;
        Set<Operation> operations;

        enum Operation {
            GET_LOADLOADFENCE(
                    "field;" + LNSEP + "VarHandle.loadLoadFence();",
                    LoadLoadFence
            ),

            STORESTOREFENCE_SET(
                    "VarHandle.storeStoreFence();" + LNSEP + "field = \\$valueLiteral1\\$;",
                    StoreStoreFence
            ),

            SET_STORESTOREFENCE(
                    "field = \\$valueLiteral1\\$;" + LNSEP + "VarHandle.storeStoreFence();",
                    StoreStoreFence
            ),

            GET_ACQUIREFENCE(
                    "field;" + LNSEP + "VarHandle.acquireFence();",
                    AcquireFence
            ),

            RELEASEFENCE_SET(
                    "VarHandle.releaseFence();" + LNSEP + "field = \\$valueLiteral1\\$;",
                    ReleaseFence
            ),

            SET_RELEASEFENCE(
                    "field = \\$valueLiteral1\\$;" + LNSEP + "VarHandle.releaseFence();",
                    ReleaseFence
            ),

            SET_FULLFENCE(
                    "field = \\$valueLiteral1\\$;" + LNSEP + "VarHandle.fullFence();",
                    FullFence
            ),

            FULLFENCE_SET(
                    "VarHandle.fullFence();" + LNSEP + "field = \\$valueLiteral1\\$;",
                    FullFence
            ),

            GET_FULLFENCE(
                    "field;" + LNSEP + "VarHandle.fullFence();",
                    FullFence
            ),

            ;

            Operation(String operation, Method method) {
                this.operation = operation;
                this.method = method;
            }

            String operation;
            Method method;
        }
    }

}
