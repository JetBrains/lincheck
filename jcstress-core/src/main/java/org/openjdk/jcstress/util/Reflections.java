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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

public class Reflections {

    private static volatile boolean RESOURCE_INITED;
    private static Set<String> RESOURCES;

    private static void ensureResourceInited() throws IOException {
        if (!RESOURCE_INITED) {
            final SortedSet<String> newResources = new TreeSet<>();
            try {
                enumerate(newResources::add);
            } catch (Throwable t) {
                throw new IOException(t);
            }
            RESOURCES = newResources;
            RESOURCE_INITED = true;
        }
    }

    public static Collection<Class<?>> getClasses(final String filter) throws IOException {
        final List<Class<?>> newClasses = new ArrayList<>();
        for (String name : getClassNames(filter)) {
            try {
                if (name.contains("sun.misc")) continue;
                if (name.contains("jdk.internal")) continue;
                newClasses.add(Class.forName(name, false, Reflections.class.getClassLoader()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            } catch (NoClassDefFoundError e) {
                // may happen in JDK 9+ while trying to load a privileged class
            } catch (NoSuchMethodError | NoSuchFieldError | ClassFormatError e) {
                // may happen when trying to load a class from a newer JDK
            }
        }
        return newClasses;
    }

    public static Collection<String> getClassNames(final String filter) throws IOException {
        ensureResourceInited();

        Pattern p = Pattern.compile("[\\\\|/]");
        final List<String> newClasses = new ArrayList<>();
        for (String name : RESOURCES) {
            name = p.matcher(name).replaceAll(".");
            if (name.contains(filter) && name.endsWith(".class")) {
                newClasses.add(name.substring(0, name.length() - 6));
            }
        }

        return newClasses;
    }

    public static void enumerate(ResultCallback callback) throws Throwable {
        for (String element : System.getProperty("java.class.path").split(File.pathSeparator)) {
            enumerate(callback, element);
        }
    }

    public static void enumerate(ResultCallback callback, String element) throws Throwable {
        File file = new File(element);
        if (file.getName().endsWith(".jar")) {
            enumerateJAR(file, callback);
        } else {
            enumeratePath(element, callback);
        }
    }

    private static void enumeratePath(String dir, final ResultCallback callback) throws IOException {

        List<File> dirs = new ArrayList<>();
        dirs.add(new File(dir));

        while (!dirs.isEmpty()) {

            List<File> siblings = new ArrayList<>();
            for (File d : dirs) {
                for (File f : d.listFiles()) {
                    if (f.isDirectory()) {
                        siblings.add(f);
                    }
                    callback.accept(f.toString());
                }
            }

            dirs = siblings;
        }
    }

    private static void enumerateJAR(File jar, ResultCallback callback) throws Throwable {
        try (FileInputStream fis = new FileInputStream(jar);
             BufferedInputStream bis = new BufferedInputStream(fis);
             JarInputStream jis = new JarInputStream(bis)) {
            JarEntry jarEntry;
            while ((jarEntry = jis.getNextJarEntry()) != null) {
                callback.accept(jarEntry.getName());
            }
        }
    }

    public interface ResultCallback {
        void accept(String name);
    }

}
