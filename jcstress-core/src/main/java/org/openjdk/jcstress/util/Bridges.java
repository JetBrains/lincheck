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
package org.openjdk.jcstress.util;

import java.util.Arrays;

public class Bridges {

    // isn't there a better solution, really?
    public static String toString(Object o) {
        if (!o.getClass().isArray()) {
            return o.toString();
        } else {
            if (o.getClass() == boolean[].class) return Arrays.toString((boolean[]) o);
            if (o.getClass() == byte[].class) return Arrays.toString((byte[]) o);
            if (o.getClass() == char[].class) return Arrays.toString((char[]) o);
            if (o.getClass() == float[].class) return Arrays.toString((float[]) o);
            if (o.getClass() == double[].class) return Arrays.toString((double[]) o);
            if (o.getClass() == short[].class) return Arrays.toString((short[]) o);
            if (o.getClass() == int[].class) return Arrays.toString((int[]) o);
            if (o.getClass() == long[].class) return Arrays.toString((long[]) o);
            if (o.getClass() == short[].class) return Arrays.toString((short[]) o);
            return Arrays.toString((Object[]) o);
        }
    }

}
