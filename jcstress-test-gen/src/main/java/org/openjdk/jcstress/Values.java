/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.util.HashMap;
import java.util.Map;

public class Values {

    public static final Map<String, String> DEFAULTS;
    public static final Map<String, String> DEFAULTS_LITERAL;
    public static final Map<String, String> VALUES;
    public static final Map<String, String> VALUES_LITERAL;

    static {
        DEFAULTS = new HashMap<>();
        DEFAULTS.put("boolean", "false");
        DEFAULTS.put("byte",    "0");
        DEFAULTS.put("char",    "\\\\\\u0000");
        DEFAULTS.put("short",   "0");
        DEFAULTS.put("int",     "0");
        DEFAULTS.put("float",   String.valueOf(0F));
        DEFAULTS.put("long",    "0");
        DEFAULTS.put("double",  String.valueOf(0D));
        DEFAULTS.put("String",  "null");

        DEFAULTS_LITERAL = new HashMap<>();
        DEFAULTS_LITERAL.put("boolean", "false");
        DEFAULTS_LITERAL.put("byte",    "(byte)0");
        DEFAULTS_LITERAL.put("char",    "'\\\\\\u0000'");
        DEFAULTS_LITERAL.put("short",   "(short)0");
        DEFAULTS_LITERAL.put("int",     "0");
        DEFAULTS_LITERAL.put("float",   "0F");
        DEFAULTS_LITERAL.put("long",    "0L");
        DEFAULTS_LITERAL.put("double",  "0D");
        DEFAULTS_LITERAL.put("String",  "null");

        VALUES = new HashMap<>();
        VALUES.put("boolean", "true");
        VALUES.put("byte",    "-1");
        VALUES.put("char",    "A");
        VALUES.put("short",   "-1");
        VALUES.put("int",     "-1");
        VALUES.put("float",   "2.3509528E-38");
        VALUES.put("long",    "-1");
        VALUES.put("double",  "1.39067116124321E-309");
        VALUES.put("String",  "object");

        VALUES_LITERAL = new HashMap<>();
        VALUES_LITERAL.put("boolean", "true");
        VALUES_LITERAL.put("byte",    "(byte) -1");
        VALUES_LITERAL.put("char",    "'A'");
        VALUES_LITERAL.put("short",   "(short) -1");
        VALUES_LITERAL.put("int",     "-1");
        VALUES_LITERAL.put("float",   "2.3509528E-38F");
        VALUES_LITERAL.put("long",    "-1L");
        VALUES_LITERAL.put("double",  "1.39067116124321E-309");
        VALUES_LITERAL.put("String",  "\"object\"");
    }

}
