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

import java.io.*;

public class CounterTest {

    @Test
    public void test1() {
        Counter<String> cnt = new Counter<>();
        cnt.record("Foo", 1);

        Assert.assertEquals(1, cnt.count("Foo"));
        Assert.assertEquals(1, cnt.elementSet().size());
        Assert.assertEquals("Foo", cnt.elementSet().iterator().next());
    }

    @Test
    public void test2() {
        Counter<String> cnt = new Counter<>();
        cnt.record("Foo", 2);

        Assert.assertEquals(2, cnt.count("Foo"));
        Assert.assertEquals(1, cnt.elementSet().size());
        Assert.assertEquals("Foo", cnt.elementSet().iterator().next());
    }

    @Test
    public void test3() {
        Counter<String> cnt = new Counter<>();
        cnt.record("Foo", 1);
        cnt.record("Bar", 1);

        Assert.assertEquals(1, cnt.count("Foo"));
        Assert.assertEquals(1, cnt.count("Bar"));
        Assert.assertEquals(2, cnt.elementSet().size());
    }

    @Test
    public void test4() {
        Counter<String> cnt = new Counter<>();
        for (int c = 0; c < 1000; c++) {
            cnt.record("Foo" + c, c);
        }

        for (int c = 0; c < 1000; c++) {
            Assert.assertEquals(c, cnt.count("Foo" + c));
        }

        Assert.assertEquals(1000, cnt.elementSet().size());
    }

    @Test
    public void testSerial_1() throws IOException, ClassNotFoundException {
        Counter<String> cnt = new Counter<>();
        for (int c = 0; c < 1000; c++) {
            cnt.record("Foo" + c, c);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(cnt);
        }

        try (ByteArrayInputStream bis = new ByteArrayInputStream(baos.toByteArray());
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            @SuppressWarnings("unchecked")
            Counter<String> desCnt = (Counter<String>) ois.readObject();

            for (int c = 0; c < 1000; c++) {
                Assert.assertEquals(c, desCnt.count("Foo" + c));
            }

            Assert.assertEquals(1000, desCnt.elementSet().size());
        }
    }

}
