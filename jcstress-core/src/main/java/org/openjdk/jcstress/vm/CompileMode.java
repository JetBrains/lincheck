/*
 * Copyright (c) 2021, Red Hat, Inc. All rights reserved.
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

import java.util.List;

public class CompileMode {

    public static final int MAX_MODES = 3;
    private static final int MODE_INT = 0;
    private static final int MODE_C1 = 1;
    private static final int MODE_C2 = 2;

    public static final int UNIFIED = -1;

    public static int[] casesFor(int actors, boolean c1, boolean c2) {
        int modes = 1 + (c1 ? 1 : 0) + (c2 ? 1 : 0);

        int len = 1;
        int maxLen = 1;
        for (int a = 0; a < actors; a++) {
            len *= modes;
            maxLen *= MAX_MODES;
        }

        int[] cases = new int[len];
        int idx = 0;
        for (int c = 0; c < maxLen; c++) {
            if (!c1 && hasC1(c, actors)) continue;
            if (!c2 && hasC2(c, actors)) continue;
            cases[idx++] = c;
        }
        return cases;
    }

    private static int actorMode(int mode, int actor) {
        int m = mode;
        for (int a = 0; a < actor; a++) {
            m /= MAX_MODES;
        }
        return m % MAX_MODES;
    }

    public static boolean isInt(int mode, int actor) {
        return (mode != UNIFIED) && (actorMode(mode, actor) == MODE_INT);
    }

    public static boolean isC1(int mode, int actor) {
        return (mode != UNIFIED) && (actorMode(mode, actor) == MODE_C1);
    }

    public static boolean isC2(int mode, int actor) {
        return (mode != UNIFIED) && (actorMode(mode, actor) == MODE_C2);
    }

    public static boolean isUnified(int mode) {
        return mode == UNIFIED;
    }

    public static boolean hasC2(int mode, int actors) {
        if (mode == UNIFIED) {
            return true;
        }
        for (int a = 0; a < actors; a++) {
            if (isC2(mode, a)) return true;
        }
        return false;
    }

    public static boolean hasC1(int mode, int actors) {
        if (mode == UNIFIED) {
            return true;
        }
        for (int a = 0; a < actors; a++) {
            if (isC1(mode, a)) return true;
        }
        return false;
    }

    public static String description(int mode, List<String> actorNames) {
        if (mode == UNIFIED) {
            return "unified across all actors" + System.lineSeparator();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("split");
        sb.append(System.lineSeparator());
        for (int a = 0; a < actorNames.size(); a++) {
            sb.append("    ");
            sb.append(actorNames.get(a));
            sb.append(": ");
            int v = actorMode(mode, a);
            switch (v) {
                case 0:
                    sb.append("Interpreter");
                    break;
                case 1:
                    sb.append("C1");
                    break;
                case 2:
                    sb.append("C2");
                    break;
                default:
                    throw new IllegalStateException("Unhandled mode: " + v);
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

}
