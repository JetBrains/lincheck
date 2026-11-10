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
package org.openjdk.jcstress.link;

import org.openjdk.jcstress.infra.collectors.TestResult;
import org.openjdk.jcstress.infra.runners.ForkedTestConfig;

import java.io.*;
import java.net.Socket;

public final class BinaryLinkClient {

    private static final int LINK_TIMEOUT_MS = Integer.getInteger("jcstress.link.timeoutMs", 30 * 1000);

    private final String hostName;
    private final int hostPort;

    public BinaryLinkClient(String hostName, int hostPort) {
        this.hostName = hostName;
        this.hostPort = hostPort;
    }

    public ForkedTestConfig jobRequest(int token) throws IOException {
        try (Socket socket = new Socket(hostName, hostPort)) {
            socket.setSoTimeout(LINK_TIMEOUT_MS);
            try (OutputStream os = socket.getOutputStream();
                 DataOutputStream dos = new DataOutputStream(os)) {
                Protocol.writeTag(dos, Protocol.TAG_JOBREQUEST);
                Protocol.writeToken(dos, token);
                dos.flush();

                try (BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
                     DataInputStream dis = new DataInputStream(bis)) {
                    int tag = Protocol.readTag(dis);
                    if (tag != Protocol.TAG_TESTCONFIG) {
                        throw new IllegalStateException("Unexpected tag");
                    }
                    return new ForkedTestConfig(dis);
                }
            }
        }
    }

    public void doneResult(int token, TestResult result) throws IOException {
        try (Socket socket = new Socket(hostName, hostPort)) {
            socket.setSoTimeout(LINK_TIMEOUT_MS);
            try (OutputStream os = socket.getOutputStream();
                 DataOutputStream dos = new DataOutputStream(os)) {
                Protocol.writeTag(dos, Protocol.TAG_RESULTS);
                Protocol.writeToken(dos, token);
                result.write(dos);
                dos.flush();

                try (BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
                     DataInputStream dis = new DataInputStream(bis)) {
                    int tag = Protocol.readTag(dis);
                    if (tag != Protocol.TAG_OK) {
                        throw new IllegalStateException("Unexpected tag");
                    }
                }
            }
        }
    }

}
