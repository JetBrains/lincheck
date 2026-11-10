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
package org.openjdk.jcstress.infra.results;

import org.junit.Assert;
import org.junit.Test;

public class I_Result_Test {

    @Test
    public void testEquals() {
        I_Result res1 = new I_Result();
        res1.r1 = 1;

        I_Result res2 = new I_Result();
        res2.r1 = 1;

        I_Result res3 = new I_Result();
        res3.r1 = 2;

        Assert.assertEquals(res1, res2);
        Assert.assertEquals(res1.hashCode(), res2.hashCode());
        Assert.assertNotEquals(res3, res1);
        Assert.assertNotEquals(res3, res2);
    }

    @Test
    public void testCopy() {
        I_Result res1 = new I_Result();
        res1.r1 = 1;

        Object res2 = res1.copy();
        Assert.assertEquals(res1, res2);
        Assert.assertEquals(res1.hashCode(), res2.hashCode());
        res1.r1 = 2;
        Assert.assertNotEquals(res1, res2);
    }

}
