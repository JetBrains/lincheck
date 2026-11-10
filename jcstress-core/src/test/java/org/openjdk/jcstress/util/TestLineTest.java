/*
 * Copyright (c) 2014, 2021, Oracle and/or its affiliates. All rights reserved.
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

public class TestLineTest {

    @Test
    public void test() {
        TestLineWriter writer = new TestLineWriter();
        writer.put("jcstress");
        writer.put("is cool in the year");
        writer.put(2016);
        writer.put(" = ");
        writer.put(true);
        writer.put("");
        writer.put("Yeah.");

        String s = writer.get();

        TestLineReader reader = new TestLineReader(s);

        Assert.assertEquals("jcstress", reader.nextString());
        Assert.assertEquals("is cool in the year", reader.nextString());
        Assert.assertEquals(2016, reader.nextInt());
        Assert.assertEquals(" = ", reader.nextString());
        Assert.assertEquals(true, reader.nextBoolean());
        Assert.assertEquals("", reader.nextString());
        Assert.assertEquals("Yeah.", reader.nextString());
    }

}
