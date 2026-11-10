/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
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

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

public class TimeValue implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long time;
    private final TimeUnit tu;

    public TimeValue(long time, TimeUnit tu) {
        if (time < 0) {
            throw new IllegalArgumentException("Time should not be negative: " + time);
        }
        this.time = time;
        this.tu = tu;
    }

    // Called by command line parser
    public static TimeValue valueOf(String str) {
        if (str == null) {
            throw new IllegalArgumentException("Option should not be null");
        }
        if (str.contains("s")) {
            return new TimeValue(Integer.parseInt(str.substring(0, str.indexOf("s"))), TimeUnit.SECONDS);
        } else if (str.contains("m")) {
            return new TimeValue(Integer.parseInt(str.substring(0, str.indexOf("m"))), TimeUnit.MINUTES);
        } else if (str.contains("h")) {
            return new TimeValue(Integer.parseInt(str.substring(0, str.indexOf("h"))), TimeUnit.HOURS);
        } else if (str.contains("d")) {
            return new TimeValue(Integer.parseInt(str.substring(0, str.indexOf("d"))), TimeUnit.DAYS);
        } else {
            throw new IllegalArgumentException("Should specify the time suffix: s, m, h, d");
        }
    }

    public long milliseconds() {
        return TimeUnit.MILLISECONDS.convert(time, tu);
    }

    public boolean isZero() {
        return (time == 0);
    }
}
