/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.jvm.agent.analysis.*
import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.settings.BreakpointId
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.settings.isApplicableTo
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.util.Logger
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.InstructionAdapter.*
import sun.nio.ch.lincheck.*
import sun.nio.ch.lincheck.BreakpointStorage
import java.util.function.BooleanSupplier
import java.util.function.Function
import java.util.function.Supplier

internal class SnapshotBreakpointTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    config: TransformationConfiguration,
    private val breakpoints: Map<BreakpointId, SnapshotBreakpoint>,
    private val classLoader: ClassLoader,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {

    private val traceIdCapturers = TraceIdCapturerRegistry(config, classLoader)

    // Needs the type analyzer to tell when local slot 0 is still `uninitializedThis` —
    override val requiresTypeAnalyzer: Boolean = true

    /**
     * Source lines that have already emitted an injection hook in the current basic block.
     *
     * Powers the basic-block same-line dedup logic:
     * when `javac`/`kotlinc` emit the same `LINENUMBER N` directive
     * at multiple bytecode offsets for one logical statement
     * (chained calls broken across lines, ternaries, multi-operand expressions on one source line),
     * only the first of those directives within a basic block emits a hook.
     *
     * The set is cleared whenever a label that is a basic-block entry
     * (jump / switch / exception-handler target) is visited,
     * so each basic block starts fresh —
     * loop back-edges, `try/finally` exception handlers,
     * and mutually-exclusive branches with the same trailing source line each
     * fire their own hook, matching JDI's "a line may have more than one executable location" semantics:
     * https://bugs.eclipse.org/bugs/show_bug.cgi?id=88626
     *
     * No control-flow graph is needed: we only rely on which labels are referenced as
     * jump / catch targets, collected lazily by `LabelCollectorMethodVisitor` during class reading.
     */
    private val linesEmittedInBlock: MutableSet<Int> = HashSet()

    override fun visitLabel(label: Label) {
        super.visitLabel(label)
        // Reset the same-line dedup state whenever we cross a jump / switch / catch
        // target — those start a new basic block on a non-fall-through edge.
        // Labels referenced only as `LINENUMBER` / `LOCALVARIABLE` anchors must NOT reset,
        // otherwise the chained-call dedup breaks
        // (the second `LINENUMBER N` directive separated from the first only
        // by a `LINENUMBER M` anchor would no longer collapse).
        // Fall-through after a conditional branch is intentionally treated as the same block.
        if (methodInfo.labels.isJumpOrCatchTarget(label)) {
            linesEmittedInBlock.clear()
        }
    }

    override fun visitLineNumber(line: Int, start: Label) = adapter.run {
        super.visitLineNumber(line, start)

        // Basic-block same-line dedup.
        // The set is reset on every basic-block entry in `visitLabel`, so this is exactly
        // "this LINENUMBER N directive is the first hook for line N in the current basic block".
        if (!linesEmittedInBlock.add(line)) return@run

        // Lambda-shadow skip: when we are visiting a synthetic lambda body
        // whose source [line] is already covered by a non-synthetic method on the same class,
        // the user's breakpoint is already wired up at the parent's chained call site —
        // emitting again inside the lambda would double-count.
        // E.g. `Optional.orElseThrow(() -> new X())` produces a `LINENUMBER N`
        // both in the parent method `foo` and in `lambda$foo`;
        // we keep only the parent's hook.
        if (isSyntheticLambdaMethod(access, methodName) && line in methodInfo.nonSyntheticMethodLines) return@run

        val matchingBreakpoints = breakpoints.entries.filter { (_, breakpoint) ->
            breakpoint.lineNumber == line &&
            // `LincheckClassVisitor` should have already filtered the breakpoints
            // to the current className/fileName pair,
            // but we still do the check as an additional safeguard.
            breakpoint.isApplicableTo(className.toCanonicalClassName(), fileName)
        }
        for ((breakpointId, breakpoint) in matchingBreakpoints) {
            processBreakpoint(breakpointId, breakpoint)
        }
    }

    private fun GeneratorAdapter.processBreakpoint(breakpointId: BreakpointId, breakpoint: SnapshotBreakpoint) {
        // Check condition safety before emitting any breakpoint code.
        // Must happen before bytecode emission so we can cleanly skip the breakpoint if unsafe.
        if (!isConditionSafe(breakpointId, breakpoint)) return
        // TODO: in the future we can relax this check and still inject the breakpoint even if watches are unsafe,
        //   skipping the watches evaluation and continuing collecting other breakpoint data.
        if (!areWatchesSafe(breakpointId, breakpoint)) return

        Logger.debug { "Inserting snapshot breakpoint at ${breakpoint.fileName}:${breakpoint.lineNumber}" }

        // STACK: <empty>
        val exitLabel = newLabel()
        val threadDescriptorLocal = newLocal(OBJECT_TYPE)
        retrieveThreadDescriptorOrExit(threadDescriptorLocal, exitLabel)
        // STACK: <empty>

        callIfNotInsideBreakpointCondition(threadDescriptorLocal) {
            if (breakpoint.conditionClassName == null) {
                injectBreakpointHit(threadDescriptorLocal, breakpointId, breakpoint)
            } else {
                ifStatement(
                    condition = {
                        // The condition may execute code with an installed breakpoint.
                        // To not make a breakpoint hit, we track when we compute the condition.
                        // TODO: check if we can re-use ignored sections mechanism instead
                        enterBreakpointCondition(threadDescriptorLocal)
                        injectConditionCallWithTryCatch(breakpointId, breakpoint)
                        leaveBreakpointCondition(threadDescriptorLocal)
                    },
                    thenClause = {
                        injectBreakpointHit(threadDescriptorLocal, breakpointId, breakpoint)
                    }
                )
            }
        }

        visitLabel(exitLabel)
    }

    private fun GeneratorAdapter.retrieveThreadDescriptorOrExit(threadDescriptorLocal: Int, exitLabel: Label) {
        // STACK: <empty>
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        // STACK: threadDescriptor
        dup()
        storeLocal(threadDescriptorLocal)
        // STACK: threadDescriptor
        ifNull(exitLabel)
    }

    private fun GeneratorAdapter.enterBreakpointCondition(threadDescriptorLocal: Int) {
        loadLocal(threadDescriptorLocal)
        invokeStatic(Injections::enterBreakpointCondition)
    }

    private fun GeneratorAdapter.leaveBreakpointCondition(threadDescriptorLocal: Int) {
        loadLocal(threadDescriptorLocal)
        invokeStatic(Injections::leaveBreakpointCondition)
    }

    private fun GeneratorAdapter.injectConditionCall(breakpointId: BreakpointId, breakpoint: SnapshotBreakpoint) {
        // === STEP 1: Load the condition class from bytecode (transformation time) ===
        // The condition code is provided as raw bytecode in conditionCodeFragment.
        // We dynamically load it into a Class object so we can access its factory method.
        val conditionClass = loadClassesFromBytes(
            userCodeClassLoader = classLoader,
            className = breakpoint.conditionClassName!!,
            classes = breakpoint.conditionClasses!!,
        )

        // Condition safety has already been validated in visitLineNumber before bytecode emission.

        // === STEP 2: Register the condition factory (transformation time) ===
        // The condition class has a static "createFactory" method that returns a Function.
        // This factory takes captured variables as input and produces a BooleanSupplier.
        val createFactoryMethod = conditionClass.getDeclaredMethod("createFactory")
        createFactoryMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val factory = createFactoryMethod.invoke(null) as Function<Array<Any?>, BooleanSupplier>
        BreakpointStorage.registerConditionFactory(breakpointId, factory)

        // === STEP 3: Inject bytecode to push the breakpointId (runtime) ===
        // The following bytecode will execute at runtime when the breakpoint is hit.
        // Push the breakpointId so Injections.createConditionInstance can look up the factory.
        push(breakpointId)   // Stack: [breakpointId]

        // === STEP 4: Capture local variables and build Object[] array (runtime) ===
        // The condition may reference local variables from the breakpoint location.
        // We need to capture their current values and pass them to the factory.
        // Extract captured variable names directly from the condition bytecode —
        // this is the single source of truth, eliminating reliance on the wire-transmitted list.
        pushCapturedValuesArray(breakpoint.conditionCodeFragment!!, breakpoint)

        // Stack after loop: [...lookup params..., capturedValuesArray]

        // === STEP 5: Create the condition instance and evaluate it (runtime) ===
        // Call Injections.createConditionInstance(breakpointId, capturedValues).
        // This looks up the registered factory and creates a BooleanSupplier instance.
        invokeStatic(Injections::createConditionInstance)
        // Stack: [BooleanSupplier instance]

        // Cast to BooleanSupplier (for type safety)
        checkCast(BOOLEAN_SUPPLIER_TYPE)
        // Stack: [BooleanSupplier instance]

        // Call getAsBoolean() on the BooleanSupplier to evaluate the condition.
        invokeInterface(
            BOOLEAN_SUPPLIER_TYPE,
            Method(
                "getAsBoolean",
                Type.BOOLEAN_TYPE,
                emptyArray()
            )
        )
        // Stack: [boolean result] - this will be consumed by the surrounding ifStatement
    }


    private fun GeneratorAdapter.injectWatchCall(breakpointId: BreakpointId, breakpoint: SnapshotBreakpoint) {
        val watchesClass = loadClassesFromBytes(
            userCodeClassLoader = classLoader,
            className = breakpoint.watchClassName!!,
            classes = breakpoint.watchClasses!!,
        )

        val factoryMethodName = breakpoint.watchFactoryMethodName ?: "createFactory"
        val createFactoryMethod = watchesClass.getDeclaredMethod(factoryMethodName)
        createFactoryMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val factory = createFactoryMethod.invoke(null) as Function<Array<Any?>, Supplier<Any?>>
        BreakpointStorage.registerWatchFactory(breakpointId, factory)

        push(breakpointId)
        pushCapturedValuesArray(breakpoint.watchCodeFragment!!, breakpoint)
        invokeStatic(Injections::createWatchInstance)
        checkCast(SUPPLIER_TYPE)
        invokeInterface(
            SUPPLIER_TYPE,
            Method(
                "get",
                OBJECT_TYPE,
                emptyArray()
            )
        )
        // The watch supplier's get() returns the captured values as an Object[]
        // (see ExpressionCompiler), so cast straight to the array — no List unwrap.
        checkCast(OBJECT_ARRAY_TYPE)
    }

    /**
     * Wraps the condition call in a try-catch block.
     * If the condition evaluation throws an exception, it returns `false`.
     * This ensures that breakpoint conditions don't crash the application.
     */
    private fun GeneratorAdapter.injectConditionCallWithTryCatch(breakpointId: BreakpointId, breakpoint: SnapshotBreakpoint) {
        // We need to store the result in a local variable because try-catch blocks
        // require the stack to be empty at block boundaries.
        val resultLocal = newLocal(Type.BOOLEAN_TYPE)
        // Initialize to false (default if exception occurs)
        push(false)
        storeLocal(resultLocal)

        tryCatchFinally(
            tryBlock = {
                injectConditionCall(breakpointId, breakpoint)
                storeLocal(resultLocal)
            },
            exceptionType = Type.getType(Throwable::class.java),
            catchBlock = {
                // Exception is on the stack, just pop it and leave resultLocal as false
                pop()
            }
        )

        // Load the result onto the stack
        loadLocal(resultLocal)
    }


    /**
     * Evaluates watches under the breakpoint-condition guard and leaves
     * an Object[] with expression values on the stack. On evaluation failure,
     * leaves an empty Object[] so the application keeps running.
     */
    private fun GeneratorAdapter.injectWatchValuesWithTryCatch(
        threadDescriptorLocal: Int,
        breakpointId: BreakpointId,
        breakpoint: SnapshotBreakpoint,
    ) {
        if (breakpoint.watchClassName == null) {
            pushEmptyObjectArray()
            return
        }

        val resultLocal = newLocal(OBJECT_ARRAY_TYPE)
        pushEmptyObjectArray()
        storeLocal(resultLocal)

        tryCatchFinally(
            tryBlock = {
                // TODO: check if we can re-use ignored sections mechanism instead
                enterBreakpointCondition(threadDescriptorLocal)
                injectWatchCall(breakpointId, breakpoint)
                storeLocal(resultLocal)
                leaveBreakpointCondition(threadDescriptorLocal)
            },
            exceptionType = Type.getType(Throwable::class.java),
            catchBlock = {
                pop()
                leaveBreakpointCondition(threadDescriptorLocal)
            }
        )

        loadLocal(resultLocal)
    }

    private fun GeneratorAdapter.pushEmptyObjectArray() {
        push(0)
        newArray(OBJECT_TYPE)
    }

    private fun GeneratorAdapter.pushCapturedValuesArray(bytecode: ByteArray, breakpoint: SnapshotBreakpoint) {
        val argNames = extractCapturedVarNamesFromBytecode(bytecode)
        val capturedLocals = argNames.map { argName ->
            currentActiveLocalVariablesInfo.firstOrNull { it.name == argName }
                ?: throw IllegalStateException("Local variable '$argName' not found in active locals at line ${breakpoint.lineNumber}")
        }

        // TODO: the fragment below is essentially a copy-paste of `pushArray` method,
        //   but using given types and `visitVarInsn` instead of `loadLocal`;
        //   `pushArray` is not used directly because there is some problem with locals numeration;
        //   probably, we need to somehow take into account locals re-enumeration performed by `GeneratorAdapter`.
        push(capturedLocals.size)
        visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
        for (i in capturedLocals.indices) {
            dup()
            push(i)
            loadCapturedLocalValue(capturedLocals[i])
            arrayStore(OBJECT_TYPE)
        }
    }

    /**
     * Pushes the boxed value of [local] onto the stack, ready to be stored into a
     * captured-values `Object[]` (locals snapshot, or condition / watch arguments).
     *
     * Special case: a breakpoint on a constructor's `super(...)` / `this(...)` chaining-call
     * line sits before the chaining `invokespecial`, where local slot 0 still holds
     * `uninitializedThis`. In that window we substitute with
     * [Injections.UNINITIALIZED_THIS] instead; `this`'s fields aren't assigned yet, so
     * no real state is lost.
     */
    private fun GeneratorAdapter.loadCapturedLocalValue(local: LocalVariableInfo) {
        if (typeAnalyzer?.locals?.getOrNull(local.index) == UNINITIALIZED_THIS) {
            pushUninitializedThisSubstitute()
        } else {
            visitVarInsn(local.type.getOpcode(ILOAD), local.index)
            box(local.type)
        }
    }

    /**
     * Checks whether the condition bytecode for the given breakpoint is safe (has no side effects).
     * If unsafe, fires the condition-unsafety notification via [BreakpointStorage] and returns `false`.
     */
    private fun isConditionSafe(breakpointId: BreakpointId, breakpoint: SnapshotBreakpoint): Boolean {
        val conditionClassName = breakpoint.conditionClassName
            ?: return true // no condition is always safe

        val conditionClassBytes = breakpoint.conditionCodeFragment
        if (conditionClassBytes == null) {
            Logger.error {
                "Condition code for breakpoint at ${breakpoint.fileName}:${breakpoint.lineNumber} is not available"
            }
            return false
        }

        val safetyViolation = checkClassSafety(
            className = conditionClassName,
            methodName = "invoke",
            methodDescriptor = "()Z",
            classBytes = conditionClassBytes,
        )
        if (safetyViolation != null) {
            Logger.warn {
                "Breakpoint condition at ${breakpoint.fileName}:${breakpoint.lineNumber} is not safe: $safetyViolation"
            }
            BreakpointStorage.notifyBreakpointExpressionUnsafetyDetected(
                breakpointId, breakpoint, BreakpointExpressionSlot.Condition, safetyViolation,
            )
            return false
        }
        return true
    }

    /**
     * Checks whether watch expression bytecode is safe (has no side effects).
     * If unsafe, fires the same unsafety notification path as conditions and returns `false`.
     */
    private fun areWatchesSafe(breakpointId: BreakpointId, breakpoint: SnapshotBreakpoint): Boolean {
        val watchClassName = breakpoint.watchClassName
            ?: return true // no watches are always safe

        val watchesClassBytes = breakpoint.watchCodeFragment
        if (watchesClassBytes == null) {
            Logger.error {
                "Watches code for breakpoint at ${breakpoint.fileName}:${breakpoint.lineNumber} is not available"
            }
            return false
        }

        val safetyViolation = checkClassSafety(
            className = watchClassName,
            methodName = "invoke",
            methodDescriptor = "()[Ljava/lang/Object;",
            classBytes = watchesClassBytes,
            allowedFunctionCalls = { className: String, methodName: String, _: String ->
                className == watchClassName.toInternalClassName() && methodName == WATCH_VALUES_HELPER_NAME
            },
        )
        if (safetyViolation != null) {
            Logger.warn {
                "Watches at ${breakpoint.fileName}:${breakpoint.lineNumber} are not safe: $safetyViolation"
            }
            BreakpointStorage.notifyBreakpointExpressionUnsafetyDetected(
                breakpointId, breakpoint, BreakpointExpressionSlot.Watch, safetyViolation,
            )
            return false
        }
        return true
    }

    private fun checkClassSafety(
        className: String,
        methodName: String,
        methodDescriptor: String,
        classBytes: ByteArray,
        allowedFunctionCalls: FunctionCallPredicate = { _, _, _ -> false },
    ): SafetyViolation? {
        val clazz = loadClassesFromBytes(
            userCodeClassLoader = classLoader,
            className = className,
            classes = mapOf(className to classBytes),
        )
        return SideEffectChecker.checkMethodForSideEffects(
            className = className,
            methodName = methodName,
            methodDescriptor = methodDescriptor,
            allowedFunctionCalls = allowedFunctionCalls,
            // IMPORTANT: use class loader of the loaded class to resolve classes in the side-effect checker
            bytecodeProvider = { clazz.classLoader.findClassBytecode(it) },
            isClassLoaded = { it == this.className || isClassAlreadyLoaded(it, clazz.classLoader) },
        )
    }

    private fun GeneratorAdapter.callIfNotInsideBreakpointCondition(threadDescriptorLocal: Int, block: () -> Unit) {
        ifStatement(
            condition = {
                loadLocal(threadDescriptorLocal)
                invokeStatic(Injections::isNotInsideBreakpointCondition)
            },
            thenClause = {
                block()
            }
        )
    }

    private fun GeneratorAdapter.injectBreakpointHit(
        threadDescriptorLocal: Int,
        breakpointId: Int,
        breakpoint: SnapshotBreakpoint,
    ) {
        val activeLocals = currentActiveLocalVariablesInfo
        val localsArrayLocal = newLocal(OBJECT_ARRAY_TYPE)
        val watchValuesArrayLocal = newLocal(OBJECT_ARRAY_TYPE)

        // Pushes local variable values onto the stack as Object[], including
        // - this
        // - method parameters
        // - local variables

        // Push new array size
        push(activeLocals.size)

        // Create new array of size activeLocals.size
        newArray(OBJECT_TYPE)

        activeLocals.forEachIndexed { index, localVariableInfo ->
            // Duplicate reference of the newly created array, as it will be consumed by arrayStore
            dup()
            // Push the array index of where to store the variable value
            push(index)
            // Load the (boxed) variable value, substituting a sentinel for a not-yet-initialized `this`
            loadCapturedLocalValue(localVariableInfo)
            // Stores the boxed variable value in the new array
            arrayStore(OBJECT_TYPE)
        }
        storeLocal(localsArrayLocal)

        // Evaluate watches with an empty operand stack: the generated try/catch
        // block must not be surrounded by already-pushed onSnapshotLineBreakpoint
        // arguments, otherwise the verifier sees inconsistent stack-map frames at
        // the catch exit.
        injectWatchValuesWithTryCatch(threadDescriptorLocal, breakpointId, breakpoint)
        storeLocal(watchValuesArrayLocal)

        loadLocal(threadDescriptorLocal)
        loadNewCodeLocationId(createCurrentLineCodeLocation())
        loadLocal(localsArrayLocal)
        loadLocal(watchValuesArrayLocal)
        traceIdCapturers.loadTraceIdIfAvailable(adapter)
        // Push the breakpoint's integer id as a compile-time constant.
        // Used by the event tracker for O(1) hit-limit checking and condition lookup.
        push(breakpointId)
        invokeStatic(Injections::onSnapshotLineBreakpoint)
    }
}

/**
 * Extracts captured variable names from compiled condition bytecode using ASM.
 *
 * Walks the class fields, skipping synthetic outer-class references (`this$*`),
 * and remaps the synthetic `__instance` field to `"this"` so the agent looks up
 * the correct local variable in the target frame.
 *
 * This is the single source of truth for captured variable ordering — the agent
 * extracts it from the same bytecode it loads, eliminating any consistency risk
 * with a separately transmitted list.
 */
private fun extractCapturedVarNamesFromBytecode(bytecode: ByteArray): List<String> {
    val names = mutableListOf<String>()
    val classReader = ClassReader(bytecode)
    classReader.accept(object : ClassVisitor(ASM_API) {
        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            // Skip static fields (e.g. Kotlin's `Companion`) and outer-this references —
            // only instance fields correspond to captured variables.
            if (access and Opcodes.ACC_STATIC != 0) return null
            if (name.startsWith("this\$")) return null
            names.add(if (name == "__instance") "this" else name)
            return null
        }
    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)
    return names
}

private val BOOLEAN_SUPPLIER_TYPE = Type.getType(BooleanSupplier::class.java)
private val SUPPLIER_TYPE = Type.getType(Supplier::class.java)

private const val WATCH_VALUES_HELPER_NAME = "__watchValues"