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


import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.TryCatchBlockSorter;

import java.util.ArrayList;
import java.util.List;

/**
 * This transformer inserts {@link ManagedStrategy}' methods invocations.
 */
class ManagedStrategyTransformer extends ClassVisitor {
    private static final int ASM_API = Opcodes.ASM5;

    private static final Type[] NO_ARGS = new Type[]{};

    private static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final Type MANAGED_STRATEGY_HOLDER_TYPE = Type.getType(ManagedStrategyHolder.class);
    private static final Type MANAGED_STRATEGY_TYPE = Type.getType(ManagedStrategy.class);

    private static final Method CURRENT_THREAD_NUMBER_METHOD = new Method("currentThreadNumber", Type.INT_TYPE, NO_ARGS);
    private static final Method BEFORE_SHARED_VARIABLE_READ_METHOD = new Method("beforeSharedVariableRead", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE});
    private static final Method BEFORE_SHARED_VARIABLE_WRITE_METHOD = new Method("beforeSharedVariableWrite", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE});
    private static final Method BEFORE_LOCK_ACQUIRE_METHOD = new Method("beforeLockAcquire", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE, OBJECT_TYPE});
    private static final Method AFTER_LOCK_RELEASE_METHOD = new Method("afterLockRelease", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE, OBJECT_TYPE});

    private String className;
    private String fileName = "";
    private final List<StackTraceElement> codeLocations = new ArrayList<>();

    public ManagedStrategyTransformer(ClassVisitor cv) {
        super(ASM_API, cv);
    }

    public List<StackTraceElement> getCodeLocations() {
        return codeLocations;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public void visitSource(String source, String debug) {
        this.fileName = source;
        super.visitSource(source, debug);
    }

    @Override
    public MethodVisitor visitMethod(int access, String mname, String desc, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, mname, desc, signature, exceptions);
        mv = new JSRInlinerAdapter(mv, access, mname, desc, signature, exceptions);
        mv = new SharedVariableAccessMethodTransformer(mname, new GeneratorAdapter(mv, access, mname, desc));
        // TODO add other transformers
        mv = new TryCatchBlockSorter(mv, access, mname, desc, signature, exceptions);
        return mv;
    }

    class SharedVariableAccessMethodTransformer extends ManagedStrategyMethodVisitor {
        public SharedVariableAccessMethodTransformer(String methodName, GeneratorAdapter mv) {
            super(methodName, mv);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            switch (opcode) {
            case Opcodes.GETSTATIC:
            case Opcodes.GETFIELD:
                invokeBeforeSharedVariableRead();
                break;
            case Opcodes.PUTSTATIC:
            case Opcodes.PUTFIELD:
                invokeBeforeSharedVariableWrite();
                break;
            }
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitInsn(int opcode) {
            switch (opcode) {
            case Opcodes.AALOAD:
            case Opcodes.LALOAD:
            case Opcodes.FALOAD:
            case Opcodes.DALOAD:
            case Opcodes.IALOAD:
                invokeBeforeSharedVariableRead();
                break;
            case Opcodes.AASTORE:
            case Opcodes.IASTORE:
            case Opcodes.LASTORE:
            case Opcodes.FASTORE:
            case Opcodes.DASTORE:
                invokeBeforeSharedVariableWrite();
                break;
            }
            super.visitInsn(opcode);
        }
    }

    class ManagedStrategyMethodVisitor extends MethodVisitor {
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
        void invokeBeforeLockAcquire() {
            invokeOnLockAcquireOrRelease(BEFORE_LOCK_ACQUIRE_METHOD);
        }

        // STACK: monitor
        void invokeAfterLockRelease() {
            invokeOnLockAcquireOrRelease(AFTER_LOCK_RELEASE_METHOD);
        }

        // STACK: monitor
        private void invokeOnLockAcquireOrRelease(Method method) {
            int monitorLocal = mv.newLocal(OBJECT_TYPE);
            mv.dup();
            mv.storeLocal(monitorLocal);
            loadCurrentThreadNumber();
            loadNewCodeLocation();
            mv.loadLocal(monitorLocal);
            mv.invokeVirtual(MANAGED_STRATEGY_TYPE, method);
        }

        void loadStrategy() {
            mv.getStatic(MANAGED_STRATEGY_HOLDER_TYPE, "strategy", MANAGED_STRATEGY_TYPE);
        }

        void loadCurrentThreadNumber() {
            loadStrategy();
            mv.invokeVirtual(MANAGED_STRATEGY_TYPE, CURRENT_THREAD_NUMBER_METHOD);
        }

        void loadNewCodeLocation() {
            int codeLocation = codeLocations.size();
            codeLocations.add(new StackTraceElement(className, methodName, fileName, lineNumber));
            mv.push(codeLocation);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            this.lineNumber = line;
            super.visitLineNumber(line, start);
        }
    }
}