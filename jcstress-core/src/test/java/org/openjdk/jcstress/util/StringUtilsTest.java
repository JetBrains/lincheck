/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class StringUtilsTest {

    @Test
    public void test() {
        String s = "FooBar";

        Assert.assertEquals("FooBar", StringUtils.cutoff(s, 7));
        Assert.assertEquals("FooBar", StringUtils.cutoff(s, 6));
        Assert.assertEquals("Fo...", StringUtils.cutoff(s, 5));
        Assert.assertEquals("...", StringUtils.cutoff(s, 3));
    }

    @Test
    public void testGetStacktrace() {
        List<String> actual = StringUtils.getStacktrace(new NullPointerException("my message"));
        String firstLine = actual.get(0);

        Assert.assertTrue(firstLine, firstLine.startsWith("java.lang.NullPointerException: my message"));
    }

    @Test
    public void testDecodeCpuList() {
        Assert.assertEquals(Arrays.asList(0), StringUtils.decodeCpuList("0"));
        Assert.assertEquals(Arrays.asList(0, 1), StringUtils.decodeCpuList("0-1"));
        Assert.assertEquals(Arrays.asList(0, 1, 2), StringUtils.decodeCpuList("0-2"));
        Assert.assertEquals(Arrays.asList(4, 0, 1, 2), StringUtils.decodeCpuList("4,0-2"));
        Assert.assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 16, 17, 18), StringUtils.decodeCpuList("0-7,16-18"));
    }

}
