package org.jetbrains.kotlinx.lincheck.runner;

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

import kotlin.coroutines.Continuation;
import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner.*;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.TryCatchBlockSorter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.jetbrains.kotlinx.lincheck.ActorKt.isSuspendable;
import static org.objectweb.asm.Opcodes.*;

/**
 * This class is used to generate {@link TestThreadExecution thread executions}.
 */
public class TestThreadExecutionGenerator {
    private static final Type[] NO_ARGS = new Type[] {};

    private static final Type CLASS_TYPE = Type.getType(Class.class);
    private static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final Method OBJECT_GET_CLASS = new Method("getClass", CLASS_TYPE, NO_ARGS);
    private static final Type OBJECT_ARRAY_TYPE = Type.getType(Object[].class);
    private static final Type THROWABLE_TYPE = Type.getType(Throwable.class);
    private static final Type INT_ARRAY_TYPE = Type.getType(int[].class);
    private static final Method EMPTY_CONSTRUCTOR = new Method("<init>", Type.VOID_TYPE, NO_ARGS);

    private static final Type RUNNER_TYPE = Type.getType(Runner.class);
    private static final Method RUNNER_ON_START_METHOD = new Method("onStart", Type.VOID_TYPE, new Type[]{Type.INT_TYPE});
    private static final Method RUNNER_ON_FINISH_METHOD = new Method("onFinish", Type.VOID_TYPE, new Type[]{Type.INT_TYPE});

    private static final Type TEST_THREAD_EXECUTION_TYPE = Type.getType(TestThreadExecution.class);
    private static final Method TEST_THREAD_EXECUTION_CONSTRUCTOR;

    private static final Type UTILS_TYPE = Type.getType(UtilsKt.class);
    private static final Method UTILS_CONSUME_CPU = new Method("consumeCPU", Type.VOID_TYPE, new Type[] {Type.INT_TYPE});

    private static final Type RESULT_TYPE = Type.getType(Result.class);

    private static final Type NO_RESULT_TYPE = Type.getType(NoResult.class);
    private static final String NO_RESULT_CLASS_NAME = NoResult.class.getCanonicalName().replace('.', '/');

    private static final Type VOID_RESULT_TYPE = Type.getType(VoidResult.class);
    private static final String VOID_RESULT_CLASS_NAME = VoidResult.class.getCanonicalName().replace('.', '/');

    private static final String INSTANCE = "INSTANCE";

    private static final Type VALUE_RESULT_TYPE = Type.getType(ValueResult.class);
    private static final Method VALUE_RESULT_TYPE_CONSTRUCTOR = new Method("<init>", Type.VOID_TYPE, new Type[] {OBJECT_TYPE});

    private static final Type EXCEPTION_RESULT_TYPE = Type.getType(ExceptionResult.class);
    private static final Method EXCEPTION_RESULT_TYPE_CONSTRUCTOR = new Method("<init>", Type.VOID_TYPE, new Type[] {CLASS_TYPE});

    private static final Type RESULT_ARRAY_TYPE = Type.getType(Result[].class);

    private static final Type PARALLEL_THREADS_RUNNER_TYPE = Type.getType(ParallelThreadsRunner.class);
    private static final Method PARALLEL_THREADS_RUNNER_PROCESS_INVOCATION_RESULT_METHOD = new Method("processInvocationResult", RESULT_TYPE, new Type[]{ OBJECT_TYPE, Type.INT_TYPE, Type.INT_TYPE });
    private static final Method RUNNER_IS_PARALLEL_EXECUTION_COMPLETED_METHOD = new Method("isParallelExecutionCompleted", Type.BOOLEAN_TYPE, new Type[]{});

    private static int generatedClassNumber = 0;

    static {
        try {
            TEST_THREAD_EXECUTION_CONSTRUCTOR = Method.getMethod(TestThreadExecution.class.getDeclaredConstructor());
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Creates a {@link TestThreadExecution} instance with specified {@link TestThreadExecution#call()} implementation.
     */
    public static TestThreadExecution create(Runner runner, int iThread, List<Actor> actors, List<ParallelThreadsRunner.Completion> completions, boolean waitsEnabled, boolean scenarioContainsSuspendableActors) {
        String className = TestThreadExecution.class.getCanonicalName() + generatedClassNumber++;
        String internalClassName = className.replace('.', '/');
        List<Object> objArgs = new ArrayList<>();
        Class<? extends TestThreadExecution> clz = runner.classLoader.defineClass(className,
                generateClass(internalClassName, Type.getType(runner.testClass), iThread, actors, objArgs, completions, waitsEnabled, scenarioContainsSuspendableActors));
        try {
            TestThreadExecution execution = clz.newInstance();
            execution.runner = runner;
            execution.objArgs = objArgs.toArray();
            return execution;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot initialize generated execution class", e);
        }
    }

    private static byte[] generateClass(String internalClassName, Type testClassType, int iThread, List<Actor> actors,
                                        List<Object> objArgs, List<ParallelThreadsRunner.Completion> completions,
                                        boolean waitsEnabled, boolean scenarioContainsSuspendableActors)
    {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        CheckClassAdapter cca = new CheckClassAdapter(cw, false);
        cca.visit(52, ACC_PUBLIC + ACC_SUPER, internalClassName, null, TEST_THREAD_EXECUTION_TYPE.getInternalName(), null);
        generateConstructor(cca);
        generateRun(cca, testClassType, iThread, actors, objArgs, completions, waitsEnabled, scenarioContainsSuspendableActors);
        cca.visitEnd();
        return cw.toByteArray();
    }

    private static void generateConstructor(ClassVisitor cv) {
        GeneratorAdapter mv = new GeneratorAdapter(ACC_PUBLIC, EMPTY_CONSTRUCTOR, null, null, cv);
        mv.visitCode();
        mv.loadThis();
        mv.invokeConstructor(TEST_THREAD_EXECUTION_TYPE, TEST_THREAD_EXECUTION_CONSTRUCTOR);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void generateRun(ClassVisitor cv, Type testType, int iThread, List<Actor> actors,
                                    List<Object> objArgs, List<Completion> completions,
                                    boolean waitsEnabled, boolean scenarioContainsSuspendableActors)
    {
        int access = ACC_PUBLIC;
        Method m = new Method("call", RESULT_ARRAY_TYPE, NO_ARGS);
        GeneratorAdapter mv = new GeneratorAdapter(access, m,
                // Try-catch blocks sorting is required
                new TryCatchBlockSorter(cv.visitMethod(access, m.getName(), m.getDescriptor(), null, null),
                        access, m.getName(), m.getDescriptor(), null, null)
        );
        mv.visitCode();
        int resLocal = createResultArray(mv, actors.size());
        // Call runner's onStart(iThread) method
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
        mv.push(iThread);
        mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_START_METHOD);
        // Number of current operation (starts with 0)
        int iLocal = mv.newLocal(Type.INT_TYPE);
        mv.push(0);
        mv.storeLocal(iLocal);

        Label returnNoResult, launchNextActor;
        // Invoke actors
        for (int i = 0; i < actors.size(); i++) {
            launchNextActor = mv.newLabel();
            returnNoResult = mv.newLabel();
            if (scenarioContainsSuspendableActors) {
                // check whether all threads are completed or suspended
                mv.loadThis();
                mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
                mv.invokeVirtual(RUNNER_TYPE, RUNNER_IS_PARALLEL_EXECUTION_COMPLETED_METHOD);
                mv.push(true);
                mv.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, returnNoResult);
            }
            Actor actor = actors.get(i);
            // Add busy-wait before operation execution (for non-first operations only)
            if (waitsEnabled && i > 0) {
                mv.loadThis();
                mv.getField(TEST_THREAD_EXECUTION_TYPE, "waits", INT_ARRAY_TYPE);
                mv.push(i - 1);
                mv.arrayLoad(Type.INT_TYPE);
                mv.invokeStatic(UTILS_TYPE, UTILS_CONSUME_CPU);
            }
            // Start of try-catch block for exceptions which this actor should handle
            Label start, end = null, handler = null, handlerEnd = null;
            if (actor.getHandlesExceptions()) {
                start = mv.newLabel();
                end = mv.newLabel();
                handler = mv.newLabel();
                handlerEnd = mv.newLabel();
                for (Class<? extends Throwable> ec : actor.getHandledExceptions())
                    mv.visitTryCatchBlock(start, end, handler, Type.getType(ec).getInternalName());
                mv.visitLabel(start);
            }
            // Load result array and index to store the current result
            mv.loadLocal(resLocal);
            mv.push(i);
            if (scenarioContainsSuspendableActors) {
                // push the instance of ParallelThreadsRunner on stack to call it's processInvocationResult method
                mv.loadThis();
                mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
                mv.checkCast(PARALLEL_THREADS_RUNNER_TYPE);
            } else {
                // Prepare to create non-processed value result of actor invocation (in case of no suspendable actors in scenario)
                // Load type of result
                if (actor.getMethod().getReturnType() != void.class) {
                    mv.newInstance(VALUE_RESULT_TYPE);
                    mv.visitInsn(DUP);
                }
            }
            // Load test instance
            mv.loadThis();
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "testInstance", OBJECT_TYPE);
            mv.checkCast(testType);
            // Load arguments for operation
            loadArguments(mv, actor, objArgs, actor.isSuspendable() ? completions.get(i) : null);
            // Invoke operation
            Method actorMethod = Method.getMethod(actor.getMethod());
            mv.invokeVirtual(testType, actorMethod);
            mv.box(actorMethod.getReturnType()); // box if needed
            if (scenarioContainsSuspendableActors) {
                // process result of method invocation with ParallelThreadsRunner's processInvocationResult(result, iThread, i)
                mv.push(iThread);
                mv.push(i);
                mv.invokeVirtual(PARALLEL_THREADS_RUNNER_TYPE, PARALLEL_THREADS_RUNNER_PROCESS_INVOCATION_RESULT_METHOD);
            } else {
                // Create result
                if (actor.getMethod().getReturnType() == void.class) {
                    mv.pop();
                    mv.visitFieldInsn(GETSTATIC, VOID_RESULT_CLASS_NAME, INSTANCE, VOID_RESULT_TYPE.getDescriptor());
                } else {
                    mv.invokeConstructor(VALUE_RESULT_TYPE, VALUE_RESULT_TYPE_CONSTRUCTOR);
                }
            }
            // Store result to array
            mv.arrayStore(RESULT_TYPE);
            // End of try-catch block
            if (actor.getHandlesExceptions()) {
                mv.visitLabel(end);
                mv.goTo(handlerEnd);
                mv.visitLabel(handler);
                if (scenarioContainsSuspendableActors) {
                    storeExceptionResultFromSuspendableThrowable(mv, resLocal, iLocal, iThread, i);
                } else {
                    storeExceptionResultFromThrowable(mv, resLocal, iLocal);
                }
                mv.visitLabel(handlerEnd);
            }
            // Increment number of current operation
            mv.iinc(iLocal, 1);
            mv.visitJumpInsn(GOTO, launchNextActor);
            // write NoResult(wasSuspended = true) if all threads were suspended or completed
            mv.visitLabel(returnNoResult);
            mv.loadLocal(resLocal);
            mv.push(i);
            // Load no result type to create new instance of NoResult
            mv.visitFieldInsn(GETSTATIC, NO_RESULT_CLASS_NAME, INSTANCE, NO_RESULT_TYPE.getDescriptor());
            mv.arrayStore(RESULT_TYPE);
            mv.iinc(iLocal, 1);
            mv.visitJumpInsn(GOTO, launchNextActor);
            mv.visitLabel(launchNextActor);
        }
        // Call runner's onFinish(iThread) method
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
        mv.push(iThread);
        mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_FINISH_METHOD);
        // Return results
        mv.loadThis();
        mv.loadLocal(resLocal);
        mv.returnValue();
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void storeExceptionResultFromThrowable(GeneratorAdapter mv, int resLocal, int iLocal) {
        int eLocal = mv.newLocal(THROWABLE_TYPE);
        mv.storeLocal(eLocal);
        mv.loadLocal(resLocal);
        mv.loadLocal(iLocal);
        //Load exception result type to create new instance of unprocessed exception result
        mv.newInstance(EXCEPTION_RESULT_TYPE);
        mv.dup();
        // Load exception result
        mv.loadLocal(eLocal);
        mv.invokeVirtual(OBJECT_TYPE, OBJECT_GET_CLASS);
        mv.invokeConstructor(EXCEPTION_RESULT_TYPE, EXCEPTION_RESULT_TYPE_CONSTRUCTOR);
        mv.arrayStore(RESULT_TYPE);
    }

    private static void storeExceptionResultFromSuspendableThrowable(GeneratorAdapter mv, int resLocal, int iLocal, int threadId, int actorId) {
        int eLocal = mv.newLocal(THROWABLE_TYPE);
        mv.storeLocal(eLocal);
        mv.loadLocal(resLocal);
        mv.loadLocal(iLocal);
        // Load runner to call processInvocationResult method
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
        mv.checkCast(PARALLEL_THREADS_RUNNER_TYPE);
        // Load exception result
        mv.loadLocal(eLocal);
        mv.push(threadId);
        mv.push(actorId);
        // Process result
        mv.invokeVirtual(PARALLEL_THREADS_RUNNER_TYPE, PARALLEL_THREADS_RUNNER_PROCESS_INVOCATION_RESULT_METHOD);
        mv.arrayStore(RESULT_TYPE);
    }

    private static int createResultArray(GeneratorAdapter mv, int size) {
        int resLocal = mv.newLocal(RESULT_ARRAY_TYPE);
        mv.push(size);
        mv.newArray(RESULT_TYPE);
        mv.storeLocal(resLocal);
        return resLocal;
    }

    private static void loadArguments(GeneratorAdapter mv, Actor actor, List<Object> objArgs, Completion completion) {
        int nArguments = actor.getArguments().size();
        for (int j = 0; j < nArguments; j++) {
            pushArgumentOnStack(mv, objArgs, actor.getArguments().toArray()[j], actor.getMethod().getParameterTypes()[j]);
        }
        if (isSuspendable(actor.getMethod())) {
            pushArgumentOnStack(mv, objArgs, completion, Continuation.class);
        }
    }

    private static void pushArgumentOnStack(GeneratorAdapter mv, List<Object> objArgs, Object arg, Class<?> argClass) {
        if (argClass == boolean.class) {
            mv.push((boolean) arg);
        } else if (argClass == byte.class) {
            mv.push((byte) arg);
        } else if (argClass == char.class) {
            mv.push((char) arg);
        } else if (argClass == short.class) {
            mv.push((short) arg);
        } else if (argClass == int.class) {
            mv.push((int) arg);
        } else if (argClass == long.class) {
            mv.push((long) arg);
        } else if (argClass == float.class) {
            mv.push((float) arg);
        } else if (argClass == double.class) {
            mv.push((double) arg);
        } else if (argClass == String.class) {
            mv.push((String) arg);
        } else { // Object type
            mv.loadThis(); // -> this
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "objArgs", OBJECT_ARRAY_TYPE); // this -> objArgs
            mv.push(objArgs.size()); // objArgs -> objArgs, index
            mv.arrayLoad(OBJECT_TYPE); // objArgs, index -> arg
            mv.checkCast(Type.getType(argClass)); // cast object to argument type
            objArgs.add(arg);
        }
    }
}
