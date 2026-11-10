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
package org.openjdk.jcstress;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Main entry point.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("Java Concurrency Stress Tests");
        System.out.println("---------------------------------------------------------------------------------");
        printVersion(System.out);
        System.out.println();

        Options opts = new Options(args);
        if (!opts.parse()) {
            System.exit(1);
        }

        JCStress jcstress = new JCStress(opts);
        if (opts.shouldList()) {
            jcstress.listTests(opts);
        } else if (opts.shouldParse()) {
            jcstress.parseResults();
        } else {
            jcstress.run();
        }
    }

    static void printVersion(PrintStream out) {
        try (InputStream stream = Main.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (stream == null) {
                // No manifest?
                out.println("Rev: unknown");
                return;
            }
            Manifest manifest = new Manifest(stream);
            Attributes attr = manifest.getMainAttributes();
            out.printf("Rev: %s, built by %s with %s at %s%n",
                    attr.getValue("Implementation-Build"),
                    attr.getValue("Built-By"),
                    attr.getValue("Build-Jdk"),
                    attr.getValue("Build-Time")
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
