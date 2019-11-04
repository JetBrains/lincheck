package org.jetbrains.kotlinx.lincheck.strategy;

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import org.jetbrains.kotlinx.lincheck.UnsafeHolder;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.*;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.jetbrains.kotlinx.lincheck.TransformationClassLoader.TRANSFORMED_PACKAGE_INTERNAL_NAME;
import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.commons.GeneratorAdapter.*;

/**
 * This transformer inserts {@link ManagedStrategy}' methods invocations.
 */
class ManagedStrategyTransformer extends ClassVisitor {
    private static final int ASM_API = Opcodes.ASM5;

    private static final Type[] NO_ARGS = new Type[]{};

    private static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final Type MANAGED_STRATEGY_HOLDER_TYPE = Type.getType(ManagedStrategyHolder.class);
    private static final Type MANAGED_STRATEGY_TYPE = Type.getType(ManagedStrategy.class);
    private static final Type LOCAL_OBJECT_MANAGER_TYPE = Type.getType(LocalObjectManager.class);
    private static final Type UNSAFE_TYPE = Type.getType(Unsafe.class);
    private static final Type UNSAFE_LOADER_TYPE = Type.getType(UnsafeHolder.class);
    private static final Type STRING_TYPE = Type.getType(String.class);
    private static final Type CLASS_TYPE = Type.getType(Class.class);

    private static final Method CURRENT_THREAD_NUMBER_METHOD = new Method("currentThreadNumber", Type.INT_TYPE, NO_ARGS);
    private static final Method BEFORE_SHARED_VARIABLE_READ_METHOD = new Method("beforeSharedVariableRead", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE});
    private static final Method BEFORE_SHARED_VARIABLE_WRITE_METHOD = new Method("beforeSharedVariableWrite", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE});
    private static final Method BEFORE_LOCK_ACQUIRE_METHOD = new Method("beforeLockAcquire", Type.BOOLEAN_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE, OBJECT_TYPE});
    private static final Method BEFORE_LOCK_RELEASE_METHOD = new Method("beforeLockRelease", Type.BOOLEAN_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE, OBJECT_TYPE});
    private static final Method BEFORE_WAIT_METHOD = new Method("beforeWait", Type.BOOLEAN_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE, OBJECT_TYPE, Type.BOOLEAN_TYPE});
    private static final Method AFTER_NOTIFY_METHOD = new Method("afterNotify", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE, OBJECT_TYPE, Type.BOOLEAN_TYPE});
    private static final Method BEFORE_PARK_METHOD = new Method("beforePark", Type.BOOLEAN_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE, Type.BOOLEAN_TYPE});
    private static final Method AFTER_UNPARK_METHOD = new Method("afterUnpark", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE, OBJECT_TYPE});
    private static final Method BEFORE_CLASS_INITIALIZATION_METHOD = new Method("beforeClassInitialization", Type.VOID_TYPE, new Type[]{Type.INT_TYPE});
    private static final Method AFTER_CLASS_INITIALIZATION_METHOD = new Method("afterClassInitialization", Type.VOID_TYPE, new Type[]{Type.INT_TYPE});

    private static final Method NEW_LOCAL_OBJECT_METHOD = new Method("newLocalObject", Type.VOID_TYPE, new Type[]{OBJECT_TYPE});
    private static final Method DELETE_LOCAL_OBJECT_METHOD = new Method("deleteLocalObject", Type.VOID_TYPE, new Type[]{OBJECT_TYPE});
    private static final Method IS_LOCAL_OBJECT_METHOD = new Method("isLocalObject", Type.BOOLEAN_TYPE, new Type[]{OBJECT_TYPE});
    private static final Method ADD_DEPENDENCY = new Method("addDependency", Type.VOID_TYPE, new Type[]{OBJECT_TYPE, OBJECT_TYPE});


    private static final Method GET_UNSAFE_METHOD = new Method("getUnsafe", UNSAFE_TYPE, new Type[]{});
    private static final Method CLASS_FOR_NAME = new Method("forName", CLASS_TYPE, new Type[]{STRING_TYPE});

    private String className;
    private int classVersion;
    private String fileName = "";
    private final List<StackTraceElement> codeLocations;


    ManagedStrategyTransformer(ClassVisitor cv, List<StackTraceElement> codeLocations) {
        super(ASM_API, new ClassRemapper(cv, new JavaUtilRemapper()));
        this.codeLocations = codeLocations;
    }

    List<StackTraceElement> getCodeLocations() {
        return codeLocations;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        this.classVersion = version;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public void visitSource(String source, String debug) {
        this.fileName = source;
        super.visitSource(source, debug);
    }

    @Override
    public MethodVisitor visitMethod(int access, String mname, String desc, String signature, String[] exceptions) {
        // replace native method VMSupportsCS8 in AtomicLong with stub
        if ((access & ACC_NATIVE) != 0 && mname.equals("VMSupportsCS8")) {
            MethodVisitor mv = super.visitMethod(access & ~ACC_NATIVE, mname, desc, signature, exceptions);
            return new VMSupportsCS8MethodGenerator(new GeneratorAdapter(mv, access & ~ACC_NATIVE, mname, desc));
        }

        boolean isSynchronized = (access & ACC_SYNCHRONIZED) != 0;
        if (isSynchronized) {
            access ^= ACC_SYNCHRONIZED; // disable synchronized
        }

        MethodVisitor mv = super.visitMethod(access, mname, desc, signature, exceptions);
        mv = new JSRInlinerAdapter(mv, access, mname, desc, signature, exceptions);
        mv = new SynchronizedBlockTransformer(mname, new GeneratorAdapter(mv, access, mname, desc));

        if (isSynchronized) {
            // synchronized method is replaced with synchronized lock
            mv = new SynchronizedBlockAddingTransformer(mname, new GeneratorAdapter(mv, access, mname, desc), className, access, classVersion);
        }

        if ("<clinit>".equals(mname)) {
            mv = new ClassInitializationTransformer(mname, new GeneratorAdapter(mv, access, mname, desc));
        }

        mv = new WaitNotifyTransformer(mname, new GeneratorAdapter(mv, access, mname, desc));
        mv = new ParkUnparkTransformer(mname, new GeneratorAdapter(mv, access, mname, desc));
        mv = new UnsafeTransformer(new GeneratorAdapter(mv, access, mname, desc));
        // SharedVariableAccessMethodTransformer should be an earlier visitor than ClassInitializationTransformer
        // not to have suspension points before 'beforeClassInitialization' call in <clinit> block.
        mv = new LocalObjectManagingTransformer(mname, new GeneratorAdapter(mv, access, mname, desc));
        mv = new SharedVariableAccessMethodTransformer(mname, new GeneratorAdapter(mv, access, mname, desc));
        mv = new TryCatchBlockSorter(mv, access, mname, desc, signature, exceptions);
        return mv;
    }

    /**
     * Changes package of transformed classes from java/util package, excluding some
     */
    private static class JavaUtilRemapper extends Remapper {
        @Override
        public String map(String name) {
            boolean isTrustedAtomicPrimitive = TrustedAtomicPrimitives.INSTANCE.isTrustedPrimitive(name);
            // function package is not transformed, because AFU uses it and thus there will be transformation problems
            boolean inFunctionPackage = name.startsWith("java/util/function/");
            if (name.startsWith("java/util/") && !isTrustedAtomicPrimitive)
                name = TRANSFORMED_PACKAGE_INTERNAL_NAME + name;
            return name;
        }
    }

    /**
     * Generates body of a native method VMSupportsCS8
     */
    private static class VMSupportsCS8MethodGenerator extends MethodVisitor {
        GeneratorAdapter mv;

        VMSupportsCS8MethodGenerator(GeneratorAdapter mv) {
            super(ASM_API, null);
            this.mv = mv;
        }

        @Override
        public void visitEnd() {
            mv.visitCode();
            mv.push(true);  // suppose that we always have CAS for Long
            mv.returnValue();
            mv.visitMaxs(1, 0);
            mv.visitEnd();
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before reads and writes of shared variables
     */
    private class SharedVariableAccessMethodTransformer extends ManagedStrategyMethodVisitor {

        SharedVariableAccessMethodTransformer(String methodName, GeneratorAdapter mv) {
            super(methodName, mv);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            if (!isFinalField(owner, name)) {
                Label skipCodeLocation;
                switch (opcode) {
                    case Opcodes.GETSTATIC:
                        invokeBeforeSharedVariableRead();
                        break;
                    case Opcodes.GETFIELD:
                        skipCodeLocation = mv.newLabel();
                        mv.dup();
                        // possible bug here when owner is not initialized yet, however haven't succeeded in making anti-test
                        invokeOnLocalCheck();
                        mv.ifZCmp(GT, skipCodeLocation);

                        invokeBeforeSharedVariableRead();

                        mv.visitLabel(skipCodeLocation);
                        break;
                    case Opcodes.PUTSTATIC:
                        invokeBeforeSharedVariableWrite();
                        break;
                    case Opcodes.PUTFIELD:
                        skipCodeLocation = mv.newLabel();
                        // possible bug here when owner is not initialized yet, however haven't succeeded in making anti-test
                        dupOwnerOnPutField(desc);
                        invokeOnLocalCheck();
                        mv.ifZCmp(GT, skipCodeLocation);
                        invokeBeforeSharedVariableWrite();

                        mv.visitLabel(skipCodeLocation);
                        break;
                }
            }
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitInsn(int opcode) {
            Label skipCodeLocation;
            switch (opcode) {
                case Opcodes.AALOAD:
                case Opcodes.LALOAD:
                case Opcodes.FALOAD:
                case Opcodes.DALOAD:
                case Opcodes.IALOAD:
                case Opcodes.BALOAD:
                case Opcodes.CALOAD:
                case Opcodes.SALOAD:
                    skipCodeLocation = mv.newLabel();
                    mv.dup2(); // arr, ind
                    mv.pop(); // arr, ind -> arr
                    invokeOnLocalCheck();
                    mv.push(true);
                    mv.ifCmp(Type.BOOLEAN_TYPE, EQ, skipCodeLocation);

                    invokeBeforeSharedVariableRead();

                    mv.visitLabel(skipCodeLocation);
                    break;
                case Opcodes.AASTORE:
                case Opcodes.IASTORE:
                case Opcodes.FASTORE:
                case Opcodes.BASTORE:
                case Opcodes.CASTORE:
                case Opcodes.SASTORE:
                case Opcodes.LASTORE:
                case Opcodes.DASTORE:
                    skipCodeLocation = mv.newLabel();
                    dupArrayOnArrayStore(opcode);
                    invokeOnLocalCheck();
                    mv.push(true);
                    mv.ifCmp(Type.BOOLEAN_TYPE, EQ, skipCodeLocation);

                    invokeBeforeSharedVariableWrite();

                    mv.visitLabel(skipCodeLocation);
                    break;
            }

            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (TrustedAtomicPrimitives.INSTANCE.isTrustedPrimitive(owner)) {
                AtomicPrimitiveMethodType type = TrustedAtomicPrimitives.INSTANCE.classifyTrustedMethod(owner, name);
                if (type == AtomicPrimitiveMethodType.WRITE)
                    invokeBeforeSharedVariableWrite();
                else if (type == AtomicPrimitiveMethodType.READ)
                    invokeBeforeSharedVariableRead();
            }
            mv.visitMethodInsn(opcode, owner, name, desc, itf);
        }
    }

    /**
     * Replaces `Unsafe.getUnsafe` with `UnsafeHolder.getUnsafe`
     */
    private static class UnsafeTransformer extends MethodVisitor {
        private GeneratorAdapter mv;

        UnsafeTransformer(GeneratorAdapter mv) {
            super(ASM_API, mv);
            this.mv = mv;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (owner.equals("sun/misc/Unsafe") && name.equals("getUnsafe")) {
                // load Unsafe
                mv.invokeStatic(UNSAFE_LOADER_TYPE, GET_UNSAFE_METHOD);
                return;
            }
            mv.visitMethodInsn(opcode, owner, name, desc, itf);
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before monitorenter and monitorexit instructions
     */
    private class SynchronizedBlockTransformer extends ManagedStrategyMethodVisitor {
        SynchronizedBlockTransformer(String methodName, GeneratorAdapter mv) {
            super(methodName, mv);
        }

        @Override
        public void visitInsn(int opcode) {
            Label opEnd;

            switch(opcode) {
                case Opcodes.MONITORENTER:
                    opEnd = mv.newLabel();
                    Label skipMonitorEnter = mv.newLabel();
                    mv.dup();
                    invokeBeforeLockAcquire();
                    mv.ifZCmp(EQ, skipMonitorEnter);
                    mv.monitorEnter();
                    mv.goTo(opEnd);
                    mv.visitLabel(skipMonitorEnter);
                    mv.pop();
                    mv.visitLabel(opEnd);
                    break;
                case Opcodes.MONITOREXIT:
                    opEnd = mv.newLabel();
                    Label skipMonitorExit = mv.newLabel();
                    mv.dup();
                    invokeBeforeLockRelease();
                    mv.ifZCmp(EQ, skipMonitorExit);
                    mv.monitorExit();
                    mv.goTo(opEnd);
                    mv.visitLabel(skipMonitorExit);
                    mv.pop();
                    mv.visitLabel(opEnd);
                    break;
                default:
                    mv.visitInsn(opcode);
            }
        }
    }

    /**
     * Replace "method(...) {...}" with "method(...) {synchronized(this) {...} }"
     */
    private class SynchronizedBlockAddingTransformer extends ManagedStrategyMethodVisitor {
        private final String className;
        private final boolean isStatic;
        private final int classVersion;

        private final Label tryLabel = new Label();
        private final Label catchLabel = new Label();

        SynchronizedBlockAddingTransformer(String methodName, GeneratorAdapter mv, String className, int access, int classVersion) {
            super(methodName, mv);

            this.className = className;
            this.classVersion = classVersion;
            this.isStatic = (access & ACC_STATIC) != 0;
        }

        @Override
        public void visitCode() {
            super.visitCode();

            loadSynchronizedMethodMonitorOwner();
            mv.monitorEnter();

            mv.visitLabel(tryLabel);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            mv.visitLabel(catchLabel);
            loadSynchronizedMethodMonitorOwner();
            mv.monitorExit();
            mv.throwException();
            mv.visitTryCatchBlock(tryLabel, catchLabel, catchLabel, null);

            mv.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitInsn(int opcode) {
            switch (opcode) {
                case ARETURN:
                case DRETURN:
                case FRETURN:
                case IRETURN:
                case LRETURN:
                case RETURN:
                    loadSynchronizedMethodMonitorOwner();
                    mv.monitorExit();
                    break;
                default:
                    // do nothing
            }

            mv.visitInsn(opcode);

        }

        private void loadSynchronizedMethodMonitorOwner() {
            if (isStatic) {
                Type classType = Type.getType("L" + className + ";");
                if (classVersion >= V1_5) {
                    mv.visitLdcInsn(classType);
                } else {
                    mv.visitLdcInsn(classType.getClassName());
                    mv.invokeStatic(CLASS_TYPE, CLASS_FOR_NAME);
                }
            } else {
                mv.loadThis();
            }
        }
    }

    /**
     * Adds beforeClassInitialization call before method and afterClassInitialization call after method
     */
    private class ClassInitializationTransformer extends ManagedStrategyMethodVisitor {
        ClassInitializationTransformer(String methodName, GeneratorAdapter mv) {
            super(methodName, mv);
        }

        @Override
        public void visitCode() {
            invokeBeforeClassInitialization();
            super.visitCode();
        }

        @Override
        public void visitInsn(int opcode) {
            switch (opcode) {
                case ARETURN:
                case DRETURN:
                case FRETURN:
                case IRETURN:
                case LRETURN:
                case RETURN:
                    invokeAfterClassInitialization();
                    break;
                default:
                    // do nothing
            }
            mv.visitInsn(opcode);
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before wait and after notify calls
     */
    private class WaitNotifyTransformer extends ManagedStrategyMethodVisitor {
        WaitNotifyTransformer(String methodName, GeneratorAdapter mv) {
            super(methodName, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            Label afterWait = null;

            boolean isWait = isWait(opcode, name, desc);
            boolean isNotify = isNotify(opcode, name, desc);

            if (isWait) {
                afterWait = mv.newLabel();
                boolean withTimeout = !desc.equals("()V");

                int lastArgument = 0;
                int firstArgument = 0;

                if (desc.equals("(J)V")) {
                    firstArgument = mv.newLocal(Type.LONG_TYPE);
                    mv.storeLocal(firstArgument);
                } else if (desc.equals("(JI)V")) {
                    lastArgument = mv.newLocal(Type.INT_TYPE);
                    mv.storeLocal(lastArgument);
                    firstArgument = mv.newLocal(Type.LONG_TYPE);
                    mv.storeLocal(firstArgument);
                }

                mv.dup();
                invokeBeforeWait(withTimeout);

                Label beforeWait = mv.newLabel();
                mv.ifZCmp(GT, beforeWait);

                mv.pop();

                mv.goTo(afterWait);
                mv.visitLabel(beforeWait);
                if (desc.equals("(J)V")) {
                    mv.loadLocal(firstArgument);
                }
                if (desc.equals("(JI)V")) { // restore popped arguments
                    mv.loadLocal(firstArgument);
                    mv.loadLocal(lastArgument);
                }
            }
            if (isNotify)
                mv.dup();

            mv.visitMethodInsn(opcode, owner, name, desc, itf);
            if (isWait)
                mv.visitLabel(afterWait);

            if (isNotify) {
                boolean notifyAll = name.equals("notifyAll");
                invokeAfterNotify(notifyAll);
            }
        }

        private boolean isWait(int opcode, String name, String desc) {
            if (opcode == INVOKEVIRTUAL && name.equals("wait")) {
                switch (desc) {
                    case "()V":
                    case "(J)V":
                    case "(JI)V":
                        return true;
                }
            }
            return false;
        }

        private boolean isNotify(int opcode, String name, String desc) {
            boolean isNotify = opcode == INVOKEVIRTUAL && name.equals("notify") && desc.equals("()V");
            boolean isNotifyAll = opcode == INVOKEVIRTUAL && name.equals("notifyAll") && desc.equals("()V");
            return isNotify || isNotifyAll;
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before park and after unpark calls
     */
    private class ParkUnparkTransformer extends ManagedStrategyMethodVisitor {
        ParkUnparkTransformer(String methodName, GeneratorAdapter mv) {
            super(methodName, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            Label beforePark = mv.newLabel();
            Label afterPark = mv.newLabel();

            boolean isPark = owner.equals("sun/misc/Unsafe") && name.equals("park");

            if (isPark) {
                Label withoutTimeoutBranch = mv.newLabel();
                Label invokeBeforeParkEnd = mv.newLabel();
                mv.dup2();
                mv.push(0L);
                mv.ifCmp(Type.LONG_TYPE, EQ, withoutTimeoutBranch);
                mv.push(true);
                invokeBeforePark();
                mv.goTo(invokeBeforeParkEnd);
                mv.visitLabel(withoutTimeoutBranch);
                mv.push(false);
                invokeBeforePark();
                mv.visitLabel(invokeBeforeParkEnd);
                mv.ifZCmp(GT, beforePark); // park if returned true
                // delete park params
                mv.pop2(); // time
                mv.pop(); // isAbsolute
                mv.pop(); // Unsafe

                mv.goTo(afterPark);
            }

            mv.visitLabel(beforePark);

            boolean isUnpark = owner.equals("sun/misc/Unsafe") && name.equals("unpark");

            int threadLocal = 0;

            if (isUnpark) {
                mv.dup();
                threadLocal = mv.newLocal(OBJECT_TYPE);
                mv.storeLocal(threadLocal);
            }

            mv.visitMethodInsn(opcode, owner, name, desc, itf);

            mv.visitLabel(afterPark);

            if (isUnpark) {
                mv.loadLocal(threadLocal);
                invokeAfterUnpark();
            }
        }
    }

    /**
     * Track local objects for odd switch codeLocations elimination
     */
    private class LocalObjectManagingTransformer extends ManagedStrategyMethodVisitor {
        LocalObjectManagingTransformer(String methodName, GeneratorAdapter mv) {
            super(methodName, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

            boolean isInit = opcode == INVOKESPECIAL && "<init>".equals(name);
            if (isInit && "Ljava/lang/Object".equals(owner)) {
                mv.dup();
                invokeOnNewLocalObject();
            }
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            mv.visitIntInsn(opcode, operand);

            if (opcode == NEWARRAY) {
                mv.dup();
                invokeOnNewLocalObject();
            }
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            mv.visitTypeInsn(opcode, type);

            if (opcode == ANEWARRAY) {
                mv.dup();
                invokeOnNewLocalObject();
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            boolean isNotPrimitiveType = desc.startsWith("L") || desc.startsWith("[");

            if (isNotPrimitiveType && !isFinalField(owner, name)) {
                switch (opcode) {
                    case Opcodes.PUTSTATIC:
                        mv.dup();
                        invokeOnLocalObjectDelete();
                        break;
                    case Opcodes.PUTFIELD:
                        // owner, value
                        mv.dup2(); // owner, value, owner, value
                        invokeOnDependencyAddition(); // owner, value

                        break;
                }
            }

            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitInsn(int opcode) {
            int value;
            switch (opcode) {
                case Opcodes.AASTORE:
                    // array, index, value
                    value = mv.newLocal(OBJECT_TYPE);
                    mv.storeLocal(value); // array, index
                    mv.dup2(); // array, index, array, index
                    mv.pop(); // array, index, array
                    mv.loadLocal(value); // array, index, array, value
                    invokeOnDependencyAddition(); // array, index
                    mv.loadLocal(value); // array, index, value
                    break;
            }
            mv.visitInsn(opcode);
        }
    }

    private class ManagedStrategyMethodVisitor extends MethodVisitor {
        private final String methodName;
        protected final GeneratorAdapter mv;

        private int lineNumber;

        ManagedStrategyMethodVisitor(String methodName, GeneratorAdapter mv) {
            super(ASM_API, mv);
            this.methodName = methodName;
            this.mv = mv;
        }

        void invokeBeforeSharedVariableRead() {
            invokeOnSharedVariableAccess(BEFORE_SHARED_VARIABLE_READ_METHOD);
        }

        void invokeBeforeSharedVariableWrite() {
            invokeOnSharedVariableAccess(BEFORE_SHARED_VARIABLE_WRITE_METHOD);
        }

        private void invokeOnSharedVariableAccess(Method method) {
            loadStrategy();
            loadCurrentThreadNumber();
            loadNewCodeLocation();
            mv.invokeVirtual(MANAGED_STRATEGY_TYPE, method);
        }

        // STACK: monitor
        void invokeBeforeWait(boolean withTimeout) {
            invokeOnWaitOrNotify(BEFORE_WAIT_METHOD, withTimeout);
        }

        // STACK: monitor
        void invokeAfterNotify(boolean notifyAll) {
            invokeOnWaitOrNotify(AFTER_NOTIFY_METHOD, notifyAll);
        }

        // STACK: monitor
        private void invokeOnWaitOrNotify(Method method, boolean flag) {
            int monitorLocal = mv.newLocal(OBJECT_TYPE);
            mv.storeLocal(monitorLocal);
            loadStrategy();
            loadCurrentThreadNumber();
            loadNewCodeLocation();
            mv.loadLocal(monitorLocal);
            mv.push(flag);
            mv.invokeVirtual(MANAGED_STRATEGY_TYPE, method);
        }

        // STACK: withTimeout
        void invokeBeforePark() {
            int withTimeoutLocal = mv.newLocal(Type.BOOLEAN_TYPE);
            mv.storeLocal(withTimeoutLocal);
            loadStrategy();
            loadCurrentThreadNumber();
            loadNewCodeLocation();
            mv.loadLocal(withTimeoutLocal);
            mv.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_PARK_METHOD);
        }

        // STACK: thread
        void invokeAfterUnpark() {
            int threadLocal = mv.newLocal(OBJECT_TYPE);
            mv.storeLocal(threadLocal);
            loadStrategy();
            loadCurrentThreadNumber();
            loadNewCodeLocation();
            mv.loadLocal(threadLocal);
            mv.invokeVirtual(MANAGED_STRATEGY_TYPE, AFTER_UNPARK_METHOD);
        }

        // STACK: monitor
        void invokeBeforeLockAcquire() {
            invokeOnLockAcquireOrRelease(BEFORE_LOCK_ACQUIRE_METHOD);
        }

        // STACK: monitor
        void invokeBeforeLockRelease() {
            invokeOnLockAcquireOrRelease(BEFORE_LOCK_RELEASE_METHOD);
        }

        // STACK: monitor
        private void invokeOnLockAcquireOrRelease(Method method) {
            int monitorLocal = mv.newLocal(OBJECT_TYPE);
            mv.storeLocal(monitorLocal);
            loadStrategy();
            loadCurrentThreadNumber();
            loadNewCodeLocation();
            mv.loadLocal(monitorLocal);
            mv.invokeVirtual(MANAGED_STRATEGY_TYPE, method);
        }

        // STACK: object
        void invokeOnNewLocalObject() {
            int objectLocal = mv.newLocal(OBJECT_TYPE);
            mv.storeLocal(objectLocal);
            loadLocalObjectManager();
            mv.loadLocal(objectLocal);
            mv.invokeVirtual(LOCAL_OBJECT_MANAGER_TYPE, NEW_LOCAL_OBJECT_METHOD);
        }

        // STACK: object
        void invokeOnLocalObjectDelete() {
            int objectLocal = mv.newLocal(OBJECT_TYPE);
            mv.storeLocal(objectLocal);
            loadLocalObjectManager();
            mv.loadLocal(objectLocal);
            mv.invokeVirtual(LOCAL_OBJECT_MANAGER_TYPE, DELETE_LOCAL_OBJECT_METHOD);
        }

        // STACK: object
        void invokeOnLocalCheck() {
            int objectLocal = mv.newLocal(OBJECT_TYPE);
            mv.storeLocal(objectLocal);
            loadLocalObjectManager();
            mv.loadLocal(objectLocal);
            mv.invokeVirtual(LOCAL_OBJECT_MANAGER_TYPE, IS_LOCAL_OBJECT_METHOD);
        }

        // STACK: owner, dependant
        void invokeOnDependencyAddition() {
            int ownerLocal = mv.newLocal(OBJECT_TYPE);
            int dependantLocal = mv.newLocal(OBJECT_TYPE);

            mv.storeLocal(dependantLocal);
            mv.storeLocal(ownerLocal);
            loadLocalObjectManager();
            mv.loadLocal(ownerLocal);
            mv.loadLocal(dependantLocal);

            mv.invokeVirtual(LOCAL_OBJECT_MANAGER_TYPE, ADD_DEPENDENCY);
        }

        void invokeBeforeClassInitialization() {
            loadStrategy();
            loadCurrentThreadNumber();
            mv.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_CLASS_INITIALIZATION_METHOD);
        }

        void invokeAfterClassInitialization() {
            loadStrategy();
            loadCurrentThreadNumber();
            mv.invokeVirtual(MANAGED_STRATEGY_TYPE, AFTER_CLASS_INITIALIZATION_METHOD);
        }


        void loadStrategy() {
            mv.getStatic(MANAGED_STRATEGY_HOLDER_TYPE, "strategy", MANAGED_STRATEGY_TYPE);
        }

        void loadLocalObjectManager() { mv.getStatic(MANAGED_STRATEGY_HOLDER_TYPE, "objectManager", LOCAL_OBJECT_MANAGER_TYPE); }

        void loadCurrentThreadNumber() {
            loadStrategy();
            mv.invokeVirtual(MANAGED_STRATEGY_TYPE, CURRENT_THREAD_NUMBER_METHOD);
        }

        void loadNewCodeLocation() {
            int codeLocation = codeLocations.size();
            codeLocations.add(new StackTraceElement(className, methodName, fileName, lineNumber));
            mv.push(codeLocation);
        }

        // STACK: array, index, value -> array, index, value, arr
        void dupArrayOnArrayStore(int opcode) {
            int value;
            switch (opcode) {
                case AASTORE:
                    value = mv.newLocal(OBJECT_TYPE);
                    break;
                case IASTORE:
                    value = mv.newLocal(Type.INT_TYPE);
                    break;
                case FASTORE:
                    value = mv.newLocal(Type.FLOAT_TYPE);
                    break;
                case BASTORE:
                    value = mv.newLocal(Type.BOOLEAN_TYPE);
                    break;
                case CASTORE:
                    value = mv.newLocal(Type.CHAR_TYPE);
                    break;
                case SASTORE:
                    value = mv.newLocal(Type.SHORT_TYPE);
                    break;
                case LASTORE:
                    value = mv.newLocal(Type.LONG_TYPE);
                    break;
                case DASTORE:
                    value = mv.newLocal(Type.DOUBLE_TYPE);
                    break;
                default:
                    throw new IllegalStateException("Unexpected opcode: " + opcode);
            }
            mv.storeLocal(value);
            mv.dup2(); // array, index, array, index
            mv.pop(); // array, index, array
            int array = mv.newLocal(OBJECT_TYPE);
            mv.storeLocal(array); // array, index
            mv.loadLocal(value); // array, index, value
            mv.loadLocal(array); // array, index, value, array
        }

        // STACK: object, value -> object, value, object
        void dupOwnerOnPutField(String desc) {
            if (!"J".equals(desc) && !"D".equals(desc)) {
                mv.dup2();// object, value, object, value
                mv.pop(); // object, value, object
            } else {
                // double or long. Value takes 2 machine words.
                mv.dup2X1(); // value, object, value
                mv.pop2(); // value, object
                mv.dupX2(); // object, value, object
            }
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            this.lineNumber = line;
            super.visitLineNumber(line, start);
        }
    }

    private boolean isFinalField(String ownerInternal, String fieldName) {
        if (ownerInternal.startsWith(TRANSFORMED_PACKAGE_INTERNAL_NAME)) {
            ownerInternal = ownerInternal.substring(TRANSFORMED_PACKAGE_INTERNAL_NAME.length());
        }
        try {
            Class clazz = Class.forName(ownerInternal.replace('/', '.'));
            return (findField(clazz, fieldName).getModifiers()  & Modifier.FINAL) == Modifier.FINAL;
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        do {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields)
                if (field.getName().equals(fieldName))
                    return field;
            clazz = clazz.getSuperclass();
        } while(clazz != null);
        throw new NoSuchFieldException();
    }
}