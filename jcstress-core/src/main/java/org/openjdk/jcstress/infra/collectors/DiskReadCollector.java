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
package org.openjdk.jcstress.infra.collectors;

import java.io.*;
import java.util.zip.GZIPInputStream;

/**
 * Reads test state from the file.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class DiskReadCollector {

    private final TestResultCollector collector;
    private final FileInputStream fis;
    private final BufferedInputStream bis;
    private final GZIPInputStream gis;
    private final ObjectInputStream ois;

    public DiskReadCollector(String fileName, TestResultCollector collector) throws IOException {
        this.collector = collector;
        File file = new File(fileName);
        fis = new FileInputStream(file);
        bis = new BufferedInputStream(fis);
        gis = new GZIPInputStream(bis);
        ois = new ObjectInputStream(gis);
    }

    public void dump() throws IOException, ClassNotFoundException {
        Object o;
        try {
            while ((o = ois.readObject()) != null) {
                if (o instanceof TestResult) {
                    collector.add((TestResult) o);
                }
            }
        } catch (EOFException e) {
            // expected
        }
    }

    public void close() {
        try {
            ois.close();
        } catch (IOException e) {
            // expected
        }

        try {
            gis.close();
        } catch (IOException e) {
            // expected
        }

        try {
            bis.close();
        } catch (IOException e) {
            // expected
        }

        try {
            fis.close();
        } catch (IOException e) {
            // expected
        }
    }

}
