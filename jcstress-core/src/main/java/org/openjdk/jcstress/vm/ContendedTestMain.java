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
package org.openjdk.jcstress.vm;

import org.openjdk.jcstress.annotations.Result;
import org.openjdk.jcstress.util.Reflections;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.openjdk.jcstress.util.UnsafeHolder.UNSAFE;

public class ContendedTestMain {

    private static final int PADDING_WIDTH = 64;

    public static void main(String... args) throws IOException {
        List<String> msgs = Collections.synchronizedList(new ArrayList<>());

        Collection<Class<?>> classes = Reflections.getClasses("Result");
        if (classes.isEmpty()) {
            throw new IllegalStateException("Classes not found");
        }

        for (Class<?> cl : classes) {
            if (cl.getAnnotation(Result.class) == null) continue;

            List<FieldDef> fdefs = new ArrayList<>();
            for (Field f : cl.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                fdefs.add(new FieldDef(f));
            }

            Collections.sort(fdefs);

            FieldDef last = null;
            for (FieldDef fd : fdefs) {
                if (fd.offset < PADDING_WIDTH) {
                    msgs.add("Class " + cl + ": field " + fd.field.getName() + " is not padded");
                }

                if (last != null) {
                    if (Math.abs(fd.offset - last.offset) < PADDING_WIDTH) {
                        msgs.add("Class " + cl + ": fields " + fd.field.getName() + " and " + last.field.getName() + " are not padded pairwise");
                    }
                }

                last = fd;
            }
        }

        if (!msgs.isEmpty()) {
            for (String msg : msgs) {
                System.out.println(msg);
            }
            throw new IllegalStateException("@Contended does not seem to work properly");
        }
    }

    static class FieldDef implements Comparable<FieldDef> {
        final Field field;
        final long offset;

        FieldDef(Field f) {
            field = f;
            offset = UNSAFE.objectFieldOffset(f);
        }

        @Override
        public int compareTo(FieldDef o) {
            return Long.compare(offset, o.offset);
        }
    }

}
