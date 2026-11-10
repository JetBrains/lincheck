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
package org.openjdk.jcstress.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InputStreamDrainer extends Thread {

    private static final int BUF_SIZE = 1024;
    private final OutputStream out;
    private final InputStream in;

    /**
     * Create a drainer which will discard the read lines.
     *
     * @param in The input stream to drain
     */
    public InputStreamDrainer(InputStream in) {
        this(in, null);
    }

    /**
     * Create a drainer that will echo all read lines to <code>out</code>.
     *
     * @param in  The input stream to drain
     * @param out Where to drain the stream into
     */
    public InputStreamDrainer(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** Drain the stream. */
    public void run() {
        byte[] buf = new byte[BUF_SIZE];
        try {
            int read;
            while ((read = in.read(buf)) != -1) {
                if (out != null) {
                    out.write(buf, 0, read);
                }
            }
            if (out != null) {
                out.flush();
            }
        } catch (IOException ioe) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, ioe.getMessage(), ioe);
        } finally {
            try {
                in.close();
            } catch (IOException ioe) {
                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, ioe.getMessage(), ioe);
            }
        }
    }

}
