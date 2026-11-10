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
package org.openjdk.jcstress;

import org.objectweb.asm.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

public class ContendedAutoInjectorMain {

    static final String SUN_MISC_CONTENDED = "Lsun/misc/Contended;";
    static final String JDK_INTERNAL_CONTENDED = "Ljdk/internal/vm/annotation/Contended;";

    static final Set<String> EXISTS_SUN_MISC_CONTENDED = new HashSet<>();
    static final Set<String> EXISTS_JDK_INTERNAL_CONTENDED = new HashSet<>();

    public static void main(String[] args) throws IOException {
        if (args.length >= 1) {
            Path rootPath = Path.of(args[0]);

            System.out.println("Processing classes at " + rootPath);
            Files.walkFileTree(rootPath, new FileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                    if (path.toString().endsWith("class")) {
                        byte[] origBytes = Files.readAllBytes(path);

                        try {
                            if (parseAnnotations(origBytes)) {
                                System.out.println("Processing " + rootPath.relativize(path));
                                final byte[] newBytes = retransform(origBytes);
                                Files.write(path, newBytes);
                            }
                        } catch (IllegalArgumentException iae) {
                            System.err.println("Error processing: " + rootPath.relativize(path) + ": ");
                            iae.printStackTrace();
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes basicFileAttributes) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path path, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            throw new IllegalStateException("Please provide the destination dir");
        }
    }

    private static byte[] retransform(byte[] origBytes) {
        ClassReader cr = new ClassReader(origBytes);
        ClassWriter cw = new ClassWriter(cr, 0);
        ContendedAnnotator ca = new ContendedAnnotator(cw);
        cr.accept(ca, 0);
        return cw.toByteArray();
    }

    static class ClassNameTracker extends ClassVisitor  {
        public ClassNameTracker() {
            super(Opcodes.ASM9);
        }

        public ClassNameTracker(ClassWriter cw) {
            super(Opcodes.ASM9, cw);
        }

        protected String className;

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            String oldName = className;
            className = name;
            super.visitOuterClass(owner, name, descriptor);
            className = oldName;
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            String oldName = className;
            className = name;
            super.visitInnerClass(name, outerName, innerName, access);
            className = oldName;
        }
    }

    static class ContendedAnnotator extends ClassNameTracker {
        public ContendedAnnotator(ClassWriter cw) {
            super(cw);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            FieldVisitor fv = super.visitField(access, name, desc, signature, value);
            String id = className + "." + name;
            if (!EXISTS_SUN_MISC_CONTENDED.contains(id)) {
                System.out.println("  Added: " + id + " <- " + SUN_MISC_CONTENDED);
                fv.visitAnnotation(SUN_MISC_CONTENDED, true);
            } else {
                System.out.println("  Skip: " + id + " already carries " + SUN_MISC_CONTENDED);
            }
            if (!EXISTS_JDK_INTERNAL_CONTENDED.contains(id)) {
                System.out.println("  Added: " + id + " <- " + JDK_INTERNAL_CONTENDED);
                fv.visitAnnotation(JDK_INTERNAL_CONTENDED, true);
            } else {
                System.out.println("  Skip: " + id + " already carries " + JDK_INTERNAL_CONTENDED);
            }
            return fv;
        }
    }

    private static boolean parseAnnotations(byte[] origBytes) {
        ClassReader cr = new ClassReader(origBytes);
        StateDetector sd = new StateDetector();
        cr.accept(sd, 0);
        return sd.isDetected();
    }

    static class StateDetector extends ClassNameTracker {
        private boolean detected;

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (desc.equals("Lorg/openjdk/jcstress/annotations/State;")) {
                detected = true;
            }
            return super.visitAnnotation(desc, visible);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
            if (detected) {
                return new FieldEnumerator(fv, className, name);
            } else {
                return fv;
            }
        }

        public boolean isDetected() {
            return detected;
        }
    }

    static class FieldEnumerator extends FieldVisitor {
        private final String className;
        private final String fieldName;

        public FieldEnumerator(FieldVisitor fieldVisitor, String className, String fieldName) {
            super(Opcodes.ASM9, fieldVisitor);
            this.className = className;
            this.fieldName = fieldName;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            String id = className + "." + fieldName;
            if (desc.equals(SUN_MISC_CONTENDED)) {
                EXISTS_SUN_MISC_CONTENDED.add(id);
            }
            if (desc.equals(JDK_INTERNAL_CONTENDED)) {
                EXISTS_JDK_INTERNAL_CONTENDED.add(id);
            }
            return super.visitAnnotation(desc, visible);
        }
    }
}
