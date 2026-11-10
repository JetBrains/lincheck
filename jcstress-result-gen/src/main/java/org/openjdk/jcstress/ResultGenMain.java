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

import java.io.IOException;

public class ResultGenMain {

    public static final Class<?>[] CLASSES = new Class<?>[] {
                boolean.class, byte.class, short.class, char.class,
                int.class, long.class, float.class, double.class,
                Object.class,
    };

    public static void main(String[] args) throws IOException {
        if (args.length >= 1) {
            ResultGenerator rg = new ResultGenerator(args[0]);

            for (Class<?> c : CLASSES) {
                rg.generateResult(c);
                rg.generateResult(c, c);
                rg.generateResult(c, c, c);
                rg.generateResult(c, c, c, c);
                rg.generateResult(c, c, c, c, c);
                rg.generateResult(c, c, c, c, c, c);
                rg.generateResult(c, c, c, c, c, c, c);
                rg.generateResult(c, c, c, c, c, c, c, c);
            }

            for (Class<?> c1 : CLASSES) {
                for (Class<?> c2 : CLASSES) {
                    rg.generateResult(c1, c2);
                }
            }

            for (Class<?> c1 : CLASSES) {
                for (Class<?> c2 : CLASSES) {
                    for (Class<?> c3 : CLASSES) {
                        rg.generateResult(c1, c2, c3);
                    }
                }
            }
        } else {
            throw new IllegalStateException("Please provide the destination dir");
        }
    }
}
