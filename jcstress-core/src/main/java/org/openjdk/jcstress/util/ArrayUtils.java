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
import java.util.Random;

public class ArrayUtils {

    public static int[] generatePermutation(int len) {
        int[] res = new int[len];
        for (int i = 0; i < len; i++) {
            res[i] = i;
        }
        return shuffle(res);
    }

    public static int[] shuffle(int[] arr) {
        Random r = new Random();
        int[] res = arr.clone();
        for (int i = arr.length; i > 1; i--) {
            int i1 = i - 1;
            int i2 = r.nextInt(i);
            int t = res[i1];
            res[i1] = res[i2];
            res[i2] = t;
        }
        return res;
    }

    public static String[] concat(String[] arr, String value) {
        String[] newS = Arrays.copyOf(arr, arr.length + 1);
        newS[arr.length] = value;
        return newS;
    }

}
