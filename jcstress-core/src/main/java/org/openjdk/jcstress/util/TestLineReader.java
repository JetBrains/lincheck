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
package org.openjdk.jcstress.util;

public class TestLineReader {

    private final String line;
    private final boolean correct;

    private int cursor;

    public TestLineReader(String line) {
        this.line = line;
        this.correct = (line.length() > 6 && line.startsWith("JCTEST"));
        this.cursor = 6;
    }

    private int readLen() {
        StringBuilder sb = new StringBuilder();
        char c = line.charAt(cursor);
        while (Character.isDigit(c)) {
            sb.append(c);
            cursor++;
            c = line.charAt(cursor);
        }
        return Integer.valueOf(sb.toString());
    }

    private String readString(int len) {
        String s = line.substring(cursor, cursor + len);
        cursor += len;
        return s;
    }

    private char readChar() {
        return line.charAt(cursor++);
    }

    public String nextString() {
        int len = readLen();
        char tag = readChar();
        if (tag != 'S') {
            throw new IllegalStateException("expected tag = S, got = " + tag);
        }
        return readString(len);
    }

    public int nextInt() {
        int len = readLen();
        char tag = readChar();
        if (tag != 'I') {
            throw new IllegalStateException("expected tag = I, got = " + tag);
        }
        return Integer.valueOf(readString(len));
    }

    public boolean nextBoolean() {
        int len = readLen();
        char tag = readChar();
        if (tag != 'B') {
            throw new IllegalStateException("expected tag = B, got = " + tag);
        }
        char v = readChar();
        return v == 'T';
    }

    public boolean isCorrect() {
        return correct;
    }
}
