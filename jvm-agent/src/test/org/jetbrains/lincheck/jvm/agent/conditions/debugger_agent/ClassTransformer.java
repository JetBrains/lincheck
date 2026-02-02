package org.jetbrains.lincheck.jvm.agent.conditions.debugger_agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

public class ClassTransformer {
    private final String className;
    private final ClassReader reader;
    final ClassWriter writer;

    public ClassTransformer(String className, byte[] classfileBuffer, int flags, final ClassLoader loader) {
        this.className = className;
        reader = new ClassReader(classfileBuffer);
        writer = new ClassWriter(reader, flags) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader;
            }
        };
    }

    public byte[] accept(ClassVisitor visitor, int parsingOptions, boolean storeClassForDebug) {
        reader.accept(visitor, parsingOptions);
        byte[] bytes = writer.toByteArray();
        if (storeClassForDebug) {
            CaptureAgent.storeClassForDebug(className, bytes);
        }
        return bytes;
    }
}
