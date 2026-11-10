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

import org.openjdk.jcstress.util.Environment;

import java.io.*;
import java.util.zip.GZIPOutputStream;

/**
 * Dumps the test results to the disk.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class DiskWriteCollector implements TestResultCollector {

    private final FileOutputStream fos;
    private final BufferedOutputStream bos;
    private final GZIPOutputStream gos;
    private final ObjectOutputStream oos;
    private int frames;

    public DiskWriteCollector(String fileName) throws IOException {
        File file = new File(fileName);
        fos = new FileOutputStream(file);
        bos = new BufferedOutputStream(fos);
        gos = new GZIPOutputStream(bos);
        oos = new ObjectOutputStream(gos);
    }

    @Override
    public void add(TestResult result) {
        synchronized (this) {
            try {
                // reset every once in a while to keep OIS away
                // from leaking the object cache.
                if ((frames++ & 0xFFF) == 0) {
                    oos.reset();
                }

                result.setEnv(Environment.getInstance());

                oos.writeObject(result);
                oos.flush();
                gos.flush();
                bos.flush();
                fos.flush();
            } catch (IOException e) {
                // expect
            }
        }
    }

    public void close() {
        synchronized (this) {
            flushAndClose(oos);
            flushAndClose(gos);
            flushAndClose(bos);
            flushAndClose(fos);
        }
    }

    private static void flushAndClose(OutputStream stream) {
        try {
            stream.flush();
        } catch (IOException e) {
            // expect
        }

        try {
            stream.close();
        } catch (IOException e) {
            // expect
        }
    }
}
