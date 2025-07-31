/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.runner;

import kotlin.coroutines.Continuation;
import org.jetbrains.kotlinx.lincheck.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.TryCatchBlockSorter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Type.*;

/**
 * This class is used to generate {@link TestThreadExecution thread executions}.
 */
public class TestThreadExecutionGenerator {
    private static final Type[] NO_ARGS = new Type[] {};
    private static final Type OBJECT_TYPE = getType(Object.class);
    private static final Type OBJECT_ARRAY_TYPE = getType(Object[].class);
    private static final Type THROWABLE_TYPE = getType(Throwable.class);
    private static final Method EMPTY_CONSTRUCTOR = new Method("<init>", VOID_TYPE, NO_ARGS);

    private static final Type RUNNER_TYPE = getType(Runner.class);
    private static final Method RUNNER_ON_THREAD_START_METHOD = new Method("onThreadStart", VOID_TYPE, new Type[]{INT_TYPE});
    private static final Method RUNNER_ON_THREAD_FINISH_METHOD = new Method("onThreadFinish", VOID_TYPE, new Type[]{INT_TYPE});
    private static final Method RUNNER_ON_ACTOR_START = new Method("onActorStart", Type.VOID_TYPE, new Type[]{ Type.INT_TYPE });
    private static final Method RUNNER_ON_ACTOR_FINISH = new Method("onActorFinish", Type.VOID_TYPE, new Type[]{ Type.INT_TYPE });

    private static final Type TEST_THREAD_EXECUTION_TYPE = getType(TestThreadExecution.class);
    private static final Method TEST_THREAD_EXECUTION_CONSTRUCTOR;
    private static final Method TEST_THREAD_EXECUTION_INC_CLOCK = new Method("incClock", VOID_TYPE, NO_ARGS);
    private static final Method TEST_THREAD_EXECUTION_READ_CLOCKS = new Method("readClocks", VOID_TYPE, new Type[]{INT_TYPE});

    private static final Type RESULT_TYPE = getType(Result.class);

    private static final Type NO_RESULT_TYPE = getType(NoResult.class);
    private static final String NO_RESULT_CLASS_NAME = NoResult.class.getCanonicalName().replace('.', '/');

    private static final Type VOID_RESULT_TYPE = getType(VoidResult.class);
    private static final String VOID_RESULT_CLASS_NAME = VoidResult.class.getCanonicalName().replace('.', '/');

    private static final String INSTANCE = "INSTANCE";

    private static final Type VALUE_RESULT_TYPE = getType(ValueResult.class);
    private static final Method VALUE_RESULT_TYPE_CONSTRUCTOR = new Method("<init>", VOID_TYPE, new Type[] {OBJECT_TYPE});

    private static final Type EXCEPTION_RESULT_TYPE = getType(ExceptionResult.class);
    private static final Type RESULT_KT_TYPE = getType(ResultKt.class);
    private static final Method RESULT_KT_CREATE_EXCEPTION_RESULT_METHOD = new Method("createExceptionResult", EXCEPTION_RESULT_TYPE, new Type[]{THROWABLE_TYPE});

    private static final Type RESULT_ARRAY_TYPE = getType(Result[].class);

    private static final Method RESULT_WAS_SUSPENDED_GETTER_METHOD = new Method("getWasSuspended", BOOLEAN_TYPE, new Type[]{});

    private static final Type PARALLEL_THREADS_RUNNER_TYPE = getType(ParallelThreadsRunner.class);
    private static final Method PARALLEL_THREADS_RUNNER_PROCESS_INVOCATION_RESULT_METHOD = new Method("processInvocationResult", RESULT_TYPE, new Type[]{ OBJECT_TYPE, INT_TYPE, INT_TYPE });
    private static final Method RUNNER_IS_PARALLEL_EXECUTION_COMPLETED_METHOD = new Method("isParallelExecutionCompleted", BOOLEAN_TYPE, new Type[]{});

    private static final Method RUNNER_ON_ACTOR_FAILURE_METHOD = new Method("onActorFailure", VOID_TYPE, new Type[]{INT_TYPE, THROWABLE_TYPE});

    private static int generatedClassNumber = 0;

    static {
        try {
            TEST_THREAD_EXECUTION_CONSTRUCTOR = Method.getMethod(TestThreadExecution.class.getDeclaredConstructor());
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Creates a {@link TestThreadExecution} instance with specified {@link TestThreadExecution#run()} implementation.
     */
    public static TestThreadExecution create(Runner runner, int iThread, List<Actor> actors,
                                             List<Continuation> completions,
                                             boolean scenarioContainsSuspendableActors
    ) {
        String className = TestThreadExecution.class.getCanonicalName() + generatedClassNumber++;
        String internalClassName = className.replace('.', '/');
        List<Object> objArgs = new ArrayList<>();
        Class<? extends TestThreadExecution> clz = runner.getClassLoader().defineClass(className,
                generateClass(internalClassName, getType(runner.getTestClass()), iThread, actors, objArgs, completions, scenarioContainsSuspendableActors));
        try {
            TestThreadExecution execution = clz.newInstance();
            execution.iThread = iThread;
            execution.runner = runner;
            execution.objArgs = objArgs.toArray();
            return execution;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot initialize generated execution class", e);
        }
    }

    private static byte[] generateClass(String internalClassName, Type testClassType, int iThread, List<Actor> actors,
                                        List<Object> objArgs, List<Continuation> completions,
                                        boolean scenarioContainsSuspendableActors)
    {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        CheckClassAdapter cca = new CheckClassAdapter(cw, false);
        cca.visit(52, ACC_PUBLIC + ACC_SUPER, internalClassName, null, TEST_THREAD_EXECUTION_TYPE.getInternalName(), null);
        generateConstructor(cca);
        generateRun(cca, testClassType, iThread, actors, objArgs, completions, scenarioContainsSuspendableActors);
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
                                    List<Object> objArgs, List<Continuation> completions,
                                    boolean scenarioContainsSuspendableActors)
    {
        int access = ACC_PUBLIC;
        Method m = new Method("run", VOID_TYPE, NO_ARGS);
        GeneratorAdapter mv = new GeneratorAdapter(access, m,
                // Try-catch blocks sorting is required
                new TryCatchBlockSorter(cv.visitMethod(access, m.getName(), m.getDescriptor(), null, null),
                        access, m.getName(), m.getDescriptor(), null, null)
        );
        mv.visitCode();
        // Load `results`
        int resLocal = mv.newLocal(RESULT_ARRAY_TYPE);
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "results", RESULT_ARRAY_TYPE);
        mv.storeLocal(resLocal);
        // Call runner's onThreadStart(iThread) method
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
        mv.push(iThread);
        mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_THREAD_START_METHOD);

        // wrap actor's running loop in try-finally
        Label actorsRunningLoopBlockStart = mv.newLabel();
        Label actorsRunningLoopBlockEnd = mv.newLabel();
        Label actorsRunningLoopBlockFinally = mv.newLabel();
        mv.visitTryCatchBlock(actorsRunningLoopBlockStart, actorsRunningLoopBlockEnd, actorsRunningLoopBlockFinally, null);
        
        mv.visitLabel(actorsRunningLoopBlockStart);
        // Number of current operation (starts with 0)
        int iLocal = mv.newLocal(INT_TYPE);
        mv.push(0);
        mv.storeLocal(iLocal);

        // Invoke actors
        for (int i = 0; i < actors.size(); i++) {
            readClocksIfNeeded(i, mv);
            Label returnNoResult = mv.newLabel();
            if (scenarioContainsSuspendableActors) {
                // check whether all threads are completed or suspended
                mv.loadThis();
                mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
                mv.invokeVirtual(RUNNER_TYPE, RUNNER_IS_PARALLEL_EXECUTION_COMPLETED_METHOD);
                mv.push(true);
                mv.ifCmp(BOOLEAN_TYPE, GeneratorAdapter.EQ, returnNoResult);
            }
            Actor actor = actors.get(i);
            // Start of try-catch block for exceptions which this actor should handle
            Label exceptionHandler = mv.newLabel();
            Label actorCatchBlockStart = mv.newLabel();
            Label actorCatchBlockEnd = mv.newLabel();
            mv.visitTryCatchBlock(actorCatchBlockStart, actorCatchBlockEnd, exceptionHandler, THROWABLE_TYPE.getInternalName());
            mv.visitLabel(actorCatchBlockStart);
            // onActorStart call
            mv.loadThis();
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
            mv.push(iThread);
            mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_ACTOR_START);
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
                if (actor.getMethod().getReturnType() == void.class) {
                    createVoidResult(actor, mv);
                }
            } else {
                // Create result
                if (actor.getMethod().getReturnType() == void.class) {
                    createVoidResult(actor, mv);
                } else {
                    mv.invokeConstructor(VALUE_RESULT_TYPE, VALUE_RESULT_TYPE_CONSTRUCTOR);
                }
            }
            // Store result to array
            mv.arrayStore(RESULT_TYPE);
            // End of try-catch block for handled exceptions
            mv.visitLabel(actorCatchBlockEnd);
            Label skipHandlers = mv.newLabel();
            mv.goTo(skipHandlers);
            // Exception handler
            mv.visitLabel(exceptionHandler);

            int eLocal = mv.newLocal(THROWABLE_TYPE);
            mv.storeLocal(eLocal);

            // push the runner on stack to call its method
            mv.loadThis();
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
            // push iThread and exception on stack
            mv.push(iThread);
            mv.loadLocal(eLocal);
            // Fail if this exception is an internal exception
            mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_ACTOR_FAILURE_METHOD);

            mv.loadLocal(eLocal);

            if (scenarioContainsSuspendableActors) {
                storeExceptionResultFromSuspendableThrowable(mv, resLocal, iLocal, iThread, i);
            } else {
                storeExceptionResultFromThrowable(mv, resLocal, iLocal);
            }
            // End of try-catch block for all other exceptions
            mv.goTo(skipHandlers);
            mv.visitLabel(skipHandlers);
            // Invoke runner onActorFinish method
            mv.loadThis();
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
            mv.push(iThread);
            mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_ACTOR_FINISH);
            // Increment the clock
            mv.loadThis();
            mv.invokeVirtual(TEST_THREAD_EXECUTION_TYPE, TEST_THREAD_EXECUTION_INC_CLOCK);
            // Increment number of current operation
            mv.iinc(iLocal, 1);
            Label launchNextActor = mv.newLabel();
            mv.visitJumpInsn(GOTO, launchNextActor);

            // write NoResult if all threads were suspended or completed
            mv.visitLabel(returnNoResult);
            mv.loadLocal(resLocal);
            mv.push(i);
            // Load no result type to create new instance of NoResult
            mv.visitFieldInsn(GETSTATIC, NO_RESULT_CLASS_NAME, INSTANCE, NO_RESULT_TYPE.getDescriptor());
            mv.arrayStore(RESULT_TYPE);
            mv.iinc(iLocal, 1);
            mv.visitLabel(launchNextActor);
        }
        mv.visitInsn(ACONST_NULL); // push null exception value indicating normal method's termination
        mv.goTo(actorsRunningLoopBlockFinally);
        mv.visitLabel(actorsRunningLoopBlockEnd);
        
        mv.visitLabel(actorsRunningLoopBlockFinally);
        // Call runner's onThreadFinish(iThread) method
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
        mv.push(iThread);
        mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_THREAD_FINISH_METHOD);

        // Check if an exception was thrown in the actors' running loop and re-throw it
        Label methodReturnLabel = mv.newLabel();
        mv.dup();
        mv.ifNull(methodReturnLabel);
        mv.throwException(); // re-throw exception

        // Complete the method
        mv.visitLabel(methodReturnLabel);
        mv.pop(); // pop null exception value indicating normal method's termination
        mv.visitInsn(RETURN);

        mv.visitMaxs(3, 4);
        mv.visitEnd();
    }

    // `actorNumber` starts with 0
    private static void readClocksIfNeeded(int actorNumber, GeneratorAdapter mv) {
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "useClocks", BOOLEAN_TYPE);
        Label l = new Label();
        mv.visitJumpInsn(IFEQ, l);
        mv.loadThis();
        mv.push(actorNumber);
        mv.invokeVirtual(TEST_THREAD_EXECUTION_TYPE, TEST_THREAD_EXECUTION_READ_CLOCKS);
        mv.visitLabel(l);
    }

    private static void createVoidResult(Actor actor, GeneratorAdapter mv) {
        if (actor.isSuspendable()) {
            Label suspendedVoidResult = mv.newLabel();
            mv.invokeVirtual(RESULT_TYPE, RESULT_WAS_SUSPENDED_GETTER_METHOD);
            mv.push(true);
            mv.ifCmp(BOOLEAN_TYPE, GeneratorAdapter.EQ, suspendedVoidResult);
            mv.visitFieldInsn(GETSTATIC, VOID_RESULT_CLASS_NAME, INSTANCE, VOID_RESULT_TYPE.getDescriptor());
            mv.visitLabel(suspendedVoidResult);
            mv.visitFieldInsn(GETSTATIC, VOID_RESULT_CLASS_NAME, INSTANCE, VOID_RESULT_TYPE.getDescriptor());
        } else {
            mv.pop();
            mv.visitFieldInsn(GETSTATIC, VOID_RESULT_CLASS_NAME, INSTANCE, VOID_RESULT_TYPE.getDescriptor());
        }
    }

    // STACK: throwable
    private static void storeExceptionResultFromThrowable(GeneratorAdapter mv, int resLocal, int iLocal) {
        int eLocal = mv.newLocal(THROWABLE_TYPE);
        mv.storeLocal(eLocal);
        mv.loadLocal(resLocal);
        mv.loadLocal(iLocal);
        // Create exception result instance
        mv.loadLocal(eLocal);
        mv.invokeStatic(RESULT_KT_TYPE, RESULT_KT_CREATE_EXCEPTION_RESULT_METHOD);
        mv.checkCast(RESULT_TYPE);
        mv.arrayStore(RESULT_TYPE);
    }

    // STACK: throwable
    private static void storeExceptionResultFromSuspendableThrowable(GeneratorAdapter mv, int resLocal, int iLocal, int iThread, int actorId) {
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
        mv.push(iThread);
        mv.push(actorId);
        // Process result
        mv.invokeVirtual(PARALLEL_THREADS_RUNNER_TYPE, PARALLEL_THREADS_RUNNER_PROCESS_INVOCATION_RESULT_METHOD);
        mv.arrayStore(RESULT_TYPE);
    }

    private static void loadArguments(GeneratorAdapter mv, Actor actor, List<Object> objArgs, Continuation completion) {
        int nArguments = actor.getArguments().size();
        for (int j = 0; j < nArguments; j++) {
            pushArgumentOnStack(mv, objArgs, actor.getArguments().toArray()[j], actor.getMethod().getParameterTypes()[j]);
        }
        if (actor.isSuspendable()) {
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
            mv.checkCast(getType(argClass)); // cast object to argument type
            objArgs.add(arg);
        }
    }
}
