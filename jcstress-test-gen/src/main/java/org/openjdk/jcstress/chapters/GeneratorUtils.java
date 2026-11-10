/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.jcstress.chapters;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class GeneratorUtils {
    static void writeOut(String destination, String pkg, String name, String contents) throws IOException {
        Path dir = Paths.get(destination, pkg.split("\\."));
        Path file = dir.resolve(name + ".java");
        Files.createDirectories(dir);

        boolean doWrite = true;
        try {
            List<String> l = Files.readAllLines(file);
            String exists = l.stream().collect(Collectors.joining(System.lineSeparator()));
            if (contents.equals(exists)) {
                doWrite = false;
            }
        } catch (IOException e) {
            // Moving on...
        }

        if (doWrite) {
            System.out.println("Generating: " + file);
            Files.write(file, Arrays.asList(contents), Charset.defaultCharset());
        } else {
            System.out.println("Skip, no modifications: " + file);
        }
    }

    static String readFromResource(String name) throws IOException {
        InputStream is = Chapter0aTestGenerator.class.getResourceAsStream(name);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));

        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = reader.readLine()) != null) {
            sb.append(l);
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    static String upcaseFirst(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String toDescriptor(String type) {
        switch (type) {
            case "boolean": return "Z";
            case "byte":    return "B";
            case "short":   return "S";
            case "char":    return "C";
            case "int":     return "I";
            case "float":   return "F";
            case "long":    return "J";
            case "double":  return "D";
            default:        return "L";
        }
    }
}
