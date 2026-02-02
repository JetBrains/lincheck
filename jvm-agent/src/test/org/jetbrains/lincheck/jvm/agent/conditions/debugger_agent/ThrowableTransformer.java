package org.jetbrains.lincheck.jvm.agent.conditions.debugger_agent;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import static org.jetbrains.lincheck.jvm.agent.TransformationUtilsKt.ASM_API;

class ThrowableTransformer implements ClassFileTransformer {

    static final String THROWABLE_NAME = CaptureAgent.getInternalClsName(Throwable.class);

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (THROWABLE_NAME.equals(className)) {
            try {
                ClassTransformer transformer = new ClassTransformer(className, classfileBuffer, ClassWriter.COMPUTE_FRAMES, loader);

                return transformer.accept(new ClassVisitor(ASM_API, transformer.writer) {
                    @Override
                    public MethodVisitor visitMethod(final int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor superMethodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                        switch (name) {
                            case "<init>":
                                // Insert call of CaptureStorage.captureThrowable(this) in the end of constructors.
                                return new MethodVisitor(api, superMethodVisitor) {
                                    @Override
                                    public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.RETURN) {
                                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                                            CaptureAgent.invokeStorageMethod(mv, "captureThrowable");
                                        }
                                        super.visitInsn(opcode);
                                    }
                                };

                            case "printStackTrace":
                            case "lockedPrintStackTrace":
                            case "printEnclosedStackTrace":
                                // Replace getOurStackTrace() call with CaptureStorage.getAsyncStackTrace()
                                // during all kinds of stack trace printing.
                                return new MethodVisitor(api, superMethodVisitor) {
                                    @Override
                                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                                        if (name.equals("getOurStackTrace")) {
                                            CaptureAgent.invokeStorageMethod(mv, "getAsyncStackTrace");
                                        } else {
                                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                        }
                                    }
                                };

                            default:
                                return superMethodVisitor;
                        }
                    }
                }, 0, true);
            }
            catch (Exception e) {
                System.out.println("Capture agent: failed to instrument " + className);
                //noinspection CallToPrintStackTrace
                e.printStackTrace();
            }
        }
        return null;
    }
}
