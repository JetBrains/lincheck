/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.*
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.Method
import java.lang.reflect.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*

/**
 * This transformer inserts [ManagedStrategy] methods invocations.
 */
internal class ManagedStrategyTransformer(
    cv: ClassVisitor?,
    private val tracePointConstructors: MutableList<TracePointConstructor>,
    private val guarantees: List<ManagedStrategyGuarantee>,
    private val eliminateLocalObjects: Boolean,
    private val collectStateRepresentation: Boolean,
    private val constructTraceRepresentation: Boolean,
    private val codeLocationIdProvider: CodeLocationIdProvider
) : ClassVisitor(ASM_API, ClassRemapper(cv, JavaUtilRemapper())) {
    private lateinit var className: String
    private var classVersion = 0
    private var fileName: String? = null

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String, interfaces: Array<String>) {
        className = name
        classVersion = version
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitSource(source: String, debug: String?) {
        fileName = source
        super.visitSource(source, debug)
    }

    override fun visitMethod(access: Int, mname: String, desc: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
        // do not transform strategy methods
        if (isStrategyMethod(className)) return super.visitMethod(access, mname, desc, signature, exceptions)
        var access = access
        // replace native method VMSupportsCS8 in AtomicLong with our stub
        if (access and ACC_NATIVE != 0 && mname == "VMSupportsCS8") {
            val mv = super.visitMethod(access and ACC_NATIVE.inv(), mname, desc, signature, exceptions)
            return VMSupportsCS8MethodGenerator(GeneratorAdapter(mv, access and ACC_NATIVE.inv(), mname, desc))
        }
        val isSynchronized = access and ACC_SYNCHRONIZED != 0
        if (isSynchronized) {
            access = access xor ACC_SYNCHRONIZED // disable synchronized
        }
        var mv = super.visitMethod(access, mname, desc, signature, exceptions)
        mv = JSRInlinerAdapter(mv, access, mname, desc, signature, exceptions)
        mv = TryCatchBlockSorter(mv, access, mname, desc, signature, exceptions)
        mv = SynchronizedBlockTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        if (isSynchronized) {
            // synchronized method is replaced with synchronized lock
            mv = SynchronizedBlockAddingTransformer(mname, GeneratorAdapter(mv, access, mname, desc), access, classVersion)
        }
        mv = ClassInitializationTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        if (constructTraceRepresentation) mv = AFUTrackingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = AtomicPrimitiveAccessMethodTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = ManagedStrategyGuaranteeTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = CallStackTraceLoggingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = HashCodeStubTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = UnsafeTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = WaitNotifyTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = ParkUnparkTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = LocalObjectManagingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = RandomTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = SharedVariableAccessMethodTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = TimeStubTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = ThreadYieldTransformer(GeneratorAdapter(mv, access, mname, desc))
        return mv
    }



    /**
     * Generates body of a native method VMSupportsCS8.
     * Native methods in java.util can not be transformed properly, so should be replaced with stubs
     */
    private class VMSupportsCS8MethodGenerator(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, null) {
        override fun visitEnd() = adapter.run {
                visitCode()
                push(true) // suppose that we always have CAS for Long
                returnValue()
                visitMaxs(1, 0)
                visitEnd()
            }
    }

    /**
     * Adds invocations of ManagedStrategy methods before reads and writes of shared variables
     */
    private inner class SharedVariableAccessMethodTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) = adapter.run {
            if (isFinalField(owner, name) || isSuspendStateMachine(owner)) {
                super.visitFieldInsn(opcode, owner, name, desc)
                return
            }

            when (opcode) {
                GETSTATIC -> {
                    val tracePointLocal = newTracePointLocal()
                    invokeBeforeSharedVariableRead(name, tracePointLocal)

                    loadStrategy() // STACK: strategy
                    loadCurrentThreadNumber() // STACK: strategy, threadId

                    // Get memory location id
                    loadMemoryLocationLabeler()
                    push(owner)
                    push(name)
                    invokeVirtual(MEMORY_LOCATION_LABELER_TYPE, LABEL_STATIC_FIELD)
                    // STACK: strategy, threadId, memoryLocation

                    push(desc)
                    invokeVirtual(MANAGED_STRATEGY_TYPE, ON_SHARED_VARIABLE_READ_METHOD)

                    unboxOrCast(Type.getType(desc))

                    // This call is replaced with the result of `onSharedVariableRead`
                    // super.visitFieldInsn(opcode, owner, name, desc)

                    captureReadValue(desc, tracePointLocal)
                }
                GETFIELD -> {
                    val tracePointLocal = newTracePointLocal()
                    invokeBeforeSharedVariableRead(name, tracePointLocal)

                    val objectLocal = newLocal(OBJECT_TYPE)
                    storeLocal(objectLocal)

                    loadStrategy() // STACK: strategy
                    loadCurrentThreadNumber() // STACK: strategy, threadId

                    // Get memory location id
                    loadMemoryLocationLabeler()
                    loadLocal(objectLocal)
                    push(name)
                    invokeVirtual(MEMORY_LOCATION_LABELER_TYPE, LABEL_OBJECT_FIELD)
                    // STACK: strategy, threadId, memoryLocation

                    push(desc)
                    invokeVirtual(MANAGED_STRATEGY_TYPE, ON_SHARED_VARIABLE_READ_METHOD)

                    unboxOrCast(Type.getType(desc))

                    captureReadValue(desc, tracePointLocal)
                }
                PUTSTATIC -> {
                    beforeSharedVariableWrite(name, desc)

                    // STACK: value
                    val valueType = Type.getType(desc)
                    val valueLocal = newLocal(valueType)
                    storeLocal(valueLocal)
                    // STACK: <empty>

                    loadStrategy() // STACK: strategy
                    loadCurrentThreadNumber() // STACK: strategy, threadId

                    // Get memory location id
                    loadMemoryLocationLabeler()
                    push(owner)
                    push(name)
                    invokeVirtual(MEMORY_LOCATION_LABELER_TYPE, LABEL_STATIC_FIELD)
                    // STACK: strategy, threadId, memoryLocation

                    loadLocal(valueLocal)
                    box(valueType)
                    // STACK: strategy, threadId, memoryLocation, value

                    push(desc)
                    invokeVirtual(MANAGED_STRATEGY_TYPE, ON_SHARED_VARIABLE_WRITE_METHOD)

                    super.visitFieldInsn(opcode, owner, name, desc)
                    invokeMakeStateRepresentation()
                }
                PUTFIELD -> {
                    beforeSharedVariableWrite(name, desc)

                    // STACK: object value
                    val valueType = Type.getType(desc)
                    val valueLocal = newLocal(valueType)
                    storeLocal(valueLocal)
                    dup()
                    val objectLocal = newLocal(OBJECT_TYPE)
                    storeLocal(objectLocal)

                    // STACK: <empty>
                    loadStrategy() // STACK: strategy
                    loadCurrentThreadNumber() // STACK: strategy, threadId

                    // Get memory location id
                    loadMemoryLocationLabeler()
                    loadLocal(objectLocal)
                    push(name)
                    invokeVirtual(MEMORY_LOCATION_LABELER_TYPE, LABEL_OBJECT_FIELD)
                    // STACK: strategy, threadId, memoryLocation

                    loadLocal(valueLocal)
                    box(valueType)
                    // STACK: strategy, threadId, memoryLocation, value

                    push(desc)
                    invokeVirtual(MANAGED_STRATEGY_TYPE, ON_SHARED_VARIABLE_WRITE_METHOD)
                    // STACK: <empty>

                    // Restore stack and invoke write
                    loadLocal(valueLocal)
                    super.visitFieldInsn(opcode, owner, name, desc)
                    invokeMakeStateRepresentation()
                }
                else -> throw IllegalArgumentException("Unknown field opcode")
            }
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                AALOAD, LALOAD, FALOAD, DALOAD, IALOAD, BALOAD, CALOAD, SALOAD -> {
                    val tracePointLocal = newTracePointLocal()
                    // add strategy invocation only if is not a local object
                    invokeBeforeSharedVariableRead(null, tracePointLocal)

                    // STACK: array, index
                    val arrayLocal = newLocal(OBJECT_TYPE)
                    val indexLocal = newLocal(Type.INT_TYPE)
                    dup2()
                    storeLocal(indexLocal)
                    storeLocal(arrayLocal)
                    // STACK: <empty>

                    loadStrategy() // STACK: strategy
                    loadCurrentThreadNumber() // STACK: strategy, threadId

                    // Get memory location id
                    loadMemoryLocationLabeler()
                    loadLocal(arrayLocal)
                    loadLocal(indexLocal)
                    invokeVirtual(MEMORY_LOCATION_LABELER_TYPE, LABEL_ARRAY_ELEMENT)
                    // STACK: strategy, threadId, memoryLocation

                    push(getArrayLoadType(opcode).descriptor)
                    invokeVirtual(MANAGED_STRATEGY_TYPE, ON_SHARED_VARIABLE_READ_METHOD)

                    // Get rid of boxes
                    unboxOrCast(getArrayLoadType(opcode))
                    val resultLocal = newLocal(getArrayLoadType(opcode))
                    storeLocal(resultLocal)

                    // TODO: Something strange happens with byte-code without this
                    super.visitInsn(opcode)
                    val resultLocal2 = newLocal(getArrayLoadType(opcode))
                    storeLocal(resultLocal2)
                    // TODO: learn why byte-code is incorrect if the result of `onSharedVariableRead` is used
                    loadLocal(resultLocal2)

                    captureReadValue(getArrayLoadType(opcode).descriptor, tracePointLocal)
                }
                AASTORE, IASTORE, FASTORE, BASTORE, CASTORE, SASTORE, LASTORE, DASTORE -> {
                    // add strategy invocation only if is not a local object
                    beforeSharedVariableWrite(null, getArrayStoreType(opcode).descriptor)

                    // STACK: array, index, value
                    val valueType = getArrayStoreType(opcode)
                    val valueLocal = newLocal(valueType)
                    val indexLocal = newLocal(Type.INT_TYPE)
                    val arrayLocal = newLocal(OBJECT_TYPE)
                    storeLocal(valueLocal)
                    dup2()
                    storeLocal(indexLocal)
                    storeLocal(arrayLocal)
                    loadLocal(valueLocal)
                    // STACK: array, index, value

                    loadStrategy() // STACK: array, index, value, strategy
                    loadCurrentThreadNumber() // STACK: array, index, value, strategy, threadId

                    // Get memory location id
                    loadMemoryLocationLabeler()
                    loadLocal(arrayLocal)
                    loadLocal(indexLocal)
                    invokeVirtual(MEMORY_LOCATION_LABELER_TYPE, LABEL_ARRAY_ELEMENT)
                    // STACK: array, index, value, strategy, threadId, memoryLocation
                    loadLocal(valueLocal)
                    box(valueType)

                    push(getArrayStoreType(opcode).descriptor)
                    invokeVirtual(MANAGED_STRATEGY_TYPE, ON_SHARED_VARIABLE_WRITE_METHOD)
                    super.visitInsn(opcode)

                    // initialize make state representation only if is not a local object
                    invokeMakeStateRepresentation()
                }
                else -> super.visitInsn(opcode)
            }
        }

        // STACK: array, index, value -> array, index, value, arr
        private fun dupArrayOnArrayStore(opcode: Int) = adapter.run {
            val type = getArrayStoreType(opcode)
            val value = newLocal(type)
            storeLocal(value)
            dup2() // array, index, array, index
            pop() // array, index, array
            val array: Int = adapter.newLocal(OBJECT_TYPE)
            storeLocal(array) // array, index
            loadLocal(value) // array, index, value
            loadLocal(array) // array, index, value, array
        }

        // STACK: object, value -> object, value, object
        private fun dupOwnerOnPutField(desc: String) = adapter.run {
            if ("J" != desc && "D" != desc) {
                dup2() // object, value, object, value
                pop() // object, value, object
            } else {
                // double or long. Value takes 2 machine words.
                dup2X1() // value, object, value
                pop2() // value, object
                dupX2() // object, value, object
            }
        }

        // STACK: value that was read
        private fun captureReadValue(desc: String, tracePointLocal: Int?) = adapter.run {
            if (!constructTraceRepresentation) return // capture return values only when logging is enabled
            val valueType = Type.getType(desc)
            val readValue = newLocal(valueType)
            copyLocal(readValue)
            // initialize ReadCodeLocation
            loadLocal(tracePointLocal!!)
            checkCast(READ_TRACE_POINT_TYPE)
            loadLocal(readValue)
            box(valueType)
            invokeVirtual(READ_TRACE_POINT_TYPE, INITIALIZE_READ_VALUE_METHOD)
        }

        // STACK: value to be written
        private fun beforeSharedVariableWrite(fieldName: String? = null, desc: String) {
            val tracePointLocal = newTracePointLocal()
            invokeBeforeSharedVariableWrite(fieldName, tracePointLocal)
            captureWrittenValue(desc, tracePointLocal)
        }

        // STACK: value to be written
        private fun captureWrittenValue(desc: String, tracePointLocal: Int?) = adapter.run {
            if (!constructTraceRepresentation) return // capture written values only when logging is enabled
            val valueType = Type.getType(desc)
            val storedValue = newLocal(valueType)
            copyLocal(storedValue) // save store value
            // initialize WriteCodeLocation with stored value
            loadLocal(tracePointLocal!!)
            checkCast(WRITE_TRACE_POINT_TYPE)
            loadLocal(storedValue)
            box(valueType)
            invokeVirtual(WRITE_TRACE_POINT_TYPE, INITIALIZE_WRITTEN_VALUE_METHOD)
        }

        private fun getArrayStoreType(opcode: Int): Type = when (opcode) {
            AASTORE -> OBJECT_TYPE
            IASTORE -> Type.INT_TYPE
            FASTORE -> Type.FLOAT_TYPE
            BASTORE -> Type.BOOLEAN_TYPE
            CASTORE -> Type.CHAR_TYPE
            SASTORE -> Type.SHORT_TYPE
            LASTORE -> Type.LONG_TYPE
            DASTORE -> Type.DOUBLE_TYPE
            else -> throw IllegalStateException("Unexpected opcode: $opcode")
        }

        private fun getArrayLoadType(opcode: Int): Type = when (opcode) {
            AALOAD -> OBJECT_TYPE
            IALOAD -> Type.INT_TYPE
            FALOAD -> Type.FLOAT_TYPE
            BALOAD -> Type.BOOLEAN_TYPE
            CALOAD -> Type.CHAR_TYPE
            SALOAD -> Type.SHORT_TYPE
            LALOAD -> Type.LONG_TYPE
            DALOAD -> Type.DOUBLE_TYPE
            else -> throw IllegalStateException("Unexpected opcode: $opcode")
        }

        private fun invokeBeforeSharedVariableRead(fieldName: String? = null, tracePointLocal: Int?) =
            invokeBeforeSharedVariableReadOrWrite(BEFORE_SHARED_VARIABLE_READ_METHOD, tracePointLocal, READ_TRACE_POINT_TYPE) { iThread, actorId, callStackTrace, ste -> ReadTracePoint(iThread, actorId, callStackTrace, fieldName, ste)
            }

        private fun invokeBeforeSharedVariableWrite(fieldName: String? = null, tracePointLocal: Int?) =
            invokeBeforeSharedVariableReadOrWrite(BEFORE_SHARED_VARIABLE_WRITE_METHOD, tracePointLocal, WRITE_TRACE_POINT_TYPE) { iThread, actorId, callStackTrace, ste -> WriteTracePoint(iThread, actorId, callStackTrace, fieldName, ste)
            }

        private fun invokeBeforeSharedVariableReadOrWrite(
            method: Method, tracePointLocal: Int?, tracePointType: Type, codeLocationConstructor: CodeLocationTracePointConstructor
        ) {
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocationAndTracePoint(tracePointLocal, tracePointType, codeLocationConstructor)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, method)
        }

        // STACK: object
        private fun invokeIsLocalObject() {
            if (eliminateLocalObjects) {
                val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
                adapter.storeLocal(objectLocal)
                loadObjectManager()
                adapter.loadLocal(objectLocal)
                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, IS_LOCAL_OBJECT_METHOD)
            } else {
                adapter.pop()
                adapter.push(false)
            }
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods on atomic primitive calls.
     * TODO: support primitives other than those from java.util.concurrent.atomic
     */
    private inner class AtomicPrimitiveAccessMethodTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) = adapter.run {
            if (!isAtomicPrimitive(owner)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                return
            }
            val innerDescriptor = atomicPrimitiveInnerDescriptor(owner) // e.g., Int for AtomicInteger
            when (name) {
                "<init>" -> {
                    // Constructors of these atomic primitives take either zero or one argument (initial value)
                    val argumentTypes = Type.getArgumentTypes(descriptor)
                    if (argumentTypes.isEmpty()) {
                        // Do nothing. The initial value is default/
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    } else {
                        check(argumentTypes.size == 1) { "Unexpected complex constructor for $owner with descriptor $descriptor" }
                        // Copy the initial value.
                        val initialValueLocal = newLocal(argumentTypes.first())
                        copyLocal(initialValueLocal)
                        // Call the constructor.
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        // Report the initial value to managed strategy *after* the object is initialized.
                        dup()
                        loadLocal(initialValueLocal)
                        // Call onSharedVariableWrite.
                        onWrite(innerDescriptor)
                    }
                }
                "set", "lazySet" -> onWrite(innerDescriptor)
                "get" -> onRead(innerDescriptor)
                "compareAndSet", "weakCompareAndSet" -> onCompareAndSet(innerDescriptor)
                "getAndAdd" -> onGetAndAdd(innerDescriptor)
                "addAndGet" -> onAddAndGet(innerDescriptor)
                "getAndIncrement" -> {
                    when (innerDescriptor) {
                        "I" -> push(1)
                        "J" -> push(1L)
                        else -> throw IllegalStateException()
                    }
                    onGetAndAdd(innerDescriptor)
                }
                "incrementAndGet" -> {
                    when (innerDescriptor) {
                        "I" -> push(1)
                        "J" -> push(1L)
                        else -> throw IllegalStateException()
                    }
                    onAddAndGet(innerDescriptor)
                }
                "getAndDecrement" -> {
                    when (innerDescriptor) {
                        "I" -> push(-1)
                        "J" -> push(-1L)
                        else -> throw IllegalStateException()
                    }
                    onGetAndAdd(innerDescriptor)
                }
                "decrementAndGet" -> {
                    when (innerDescriptor) {
                        "I" -> push(-1)
                        "J" -> push(-1L)
                        else -> throw IllegalStateException()
                    }
                    onAddAndGet(innerDescriptor)
                }
                else -> super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }

        private fun onGetAndAdd(innerDescriptor: String) = onXXXAndXXX(innerDescriptor, ON_GET_AND_ADD_METHOD)

        private fun onAddAndGet(innerDescriptor: String) = onXXXAndXXX(innerDescriptor, ON_ADD_AND_GET_METHOD)

        private fun onXXXAndXXX(innerDescriptor: String, method: Method) = adapter.run {
            val deltaLocal = newLocal(Type.getType(innerDescriptor))
            storeLocal(deltaLocal)
            val primitiveLocal = newLocal(OBJECT_TYPE)
            storeLocal(primitiveLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            // Get memory location id.
            loadMemoryLocationLabeler()
            loadLocal(primitiveLocal)
            invokeVirtual(MEMORY_LOCATION_LABELER_TYPE, LABEL_ATOMIC_PRIMITIVE)
            loadLocal(deltaLocal)
            box(Type.getType(innerDescriptor))
            push(innerDescriptor)
            invokeVirtual(MANAGED_STRATEGY_TYPE, method)
            unboxOrCast(Type.getType(innerDescriptor))
        }

        private fun onCompareAndSet(innerDescriptor: String) = adapter.run {
            val newValueLocal = newLocal(Type.getType(innerDescriptor))
            storeLocal(newValueLocal)
            val expectedValueLocal = newLocal(Type.getType(innerDescriptor))
            storeLocal(expectedValueLocal)
            val primitiveLocal = newLocal(OBJECT_TYPE)
            storeLocal(primitiveLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            // Get memory location id.
            loadMemoryLocationLabeler()
            loadLocal(primitiveLocal)
            invokeVirtual(MEMORY_LOCATION_LABELER_TYPE, LABEL_ATOMIC_PRIMITIVE)
            loadLocal(expectedValueLocal)
            box(Type.getType(innerDescriptor))
            loadLocal(newValueLocal)
            box(Type.getType(innerDescriptor))
            push(innerDescriptor)
            invokeVirtual(MANAGED_STRATEGY_TYPE, ON_COMPARE_AND_SET_METHOD)
        }

        private fun onWrite(innerDescriptor: String) = adapter.run {
            val valueLocal = newLocal(Type.getType(innerDescriptor))
            storeLocal(valueLocal)
            val primitiveLocal = newLocal(OBJECT_TYPE)
            storeLocal(primitiveLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            // Get memory location id.
            loadMemoryLocationLabeler()
            loadLocal(primitiveLocal)
            invokeVirtual(MEMORY_LOCATION_LABELER_TYPE, LABEL_ATOMIC_PRIMITIVE)
            loadLocal(valueLocal)
            box(Type.getType(innerDescriptor))
            push(innerDescriptor)
            invokeVirtual(MANAGED_STRATEGY_TYPE, ON_SHARED_VARIABLE_WRITE_METHOD)
        }

        private fun onRead(innerDescriptor: String) = adapter.run {
            val primitiveLocal = newLocal(OBJECT_TYPE)
            storeLocal(primitiveLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            // Get memory location id.
            loadMemoryLocationLabeler()
            loadLocal(primitiveLocal)
            invokeVirtual(MEMORY_LOCATION_LABELER_TYPE, LABEL_ATOMIC_PRIMITIVE)
            push(innerDescriptor)
            invokeVirtual(MANAGED_STRATEGY_TYPE, ON_SHARED_VARIABLE_READ_METHOD)
            unboxOrCast(Type.getType(innerDescriptor))
        }

        private fun isAtomicPrimitive(owner: String): Boolean = when {
            owner.startsWith("java/util/concurrent/atomic/") ->
                owner.removePrefix("java/util/concurrent/atomic/") in listOf(
                    AtomicBoolean::class.simpleName,
                    AtomicInteger::class.simpleName,
                    AtomicReference::class.simpleName,
                    AtomicLong::class.simpleName,
                )
            else -> false
        }

        private fun atomicPrimitiveInnerDescriptor(owner: String): String = when (owner) {
            AtomicBoolean::class.qualifiedName!!.internalClassName -> "B"
            AtomicInteger::class.qualifiedName!!.internalClassName -> "I"
            AtomicLong::class.qualifiedName!!.internalClassName -> "J"
            AtomicReference::class.qualifiedName!!.internalClassName -> OBJECT_TYPE.descriptor
            else -> throw IllegalStateException("Unknown atomic primitive $owner")
        }

    }

    /**
     * Add strategy method invocations corresponding to ManagedGuarantee guarantees.
     * CallStackTraceTransformer should be an earlier transformer than this transformer, because
     * this transformer reuse code locations created by CallStackTraceTransformer.
     */
    private inner class ManagedStrategyGuaranteeTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            when (classifyGuaranteeType(owner, name)) {
                ManagedGuaranteeType.IGNORE -> {
                    runInIgnoredSection {
                        adapter.visitMethodInsn(opcode, owner, name, desc, itf)
                    }
                }
                ManagedGuaranteeType.TREAT_AS_ATOMIC -> {
                    invokeBeforeAtomicMethodCall()
                    runInIgnoredSection {
                        adapter.visitMethodInsn(opcode, owner, name, desc, itf)
                    }
                    invokeMakeStateRepresentation()
                }
                null -> adapter.visitMethodInsn(opcode, owner, name, desc, itf)
            }
        }

        /**
         * Find a guarantee that a method has if any
         */
        private fun classifyGuaranteeType(className: String, methodName: String): ManagedGuaranteeType? {
            for (guarantee in guarantees)
                if (guarantee.methodPredicate(methodName) && guarantee.classPredicate(className.canonicalClassName))
                    return guarantee.type
            return null
        }

        private fun invokeBeforeAtomicMethodCall() {
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.push(codeLocationIdProvider.lastId) // re-use previous code location
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_ATOMIC_METHOD_CALL_METHOD)
        }
    }

    /**
     * Makes all <clinit> sections ignored, because managed execution in <clinit> can lead to a deadlock.
     * SharedVariableAccessMethodTransformer should be earlier than this transformer not to create switch points before
     * beforeIgnoredSectionEntering invocations.
     */
    private inner class ClassInitializationTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter)  {
        private val isClinit = methodName == "<clinit>"

        override fun visitCode() {
            if (isClinit)
                invokeBeforeIgnoredSectionEntering()
            mv.visitCode()
        }

        override fun visitInsn(opcode: Int) {
            if (isClinit) {
                when (opcode) {
                    ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> invokeAfterIgnoredSectionLeaving()
                    else -> { }
                }
            }
            mv.visitInsn(opcode)
        }
    }

    /**
     * Adds strategy method invocations before and after method calls.
     */
    private inner class CallStackTraceLoggingTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
        private val isSuspendStateMachine by lazy { isSuspendStateMachine(className) }

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
            if (isSuspendStateMachine || isStrategyMethod(owner) || isInternalCoroutineCall(owner, name)) {
                visitMethodInsn(opcode, owner, name, desc, itf)
                return
            }
            if (!constructTraceRepresentation) {
                // just increase code location id to keep ids consistent with the ones
                // when `constructTraceRepresentation` is disabled
                codeLocationIdProvider.newId()
                visitMethodInsn(opcode, owner, name, desc, itf)
                return
            }
            val callStart = newLabel()
            val callEnd = newLabel()
            val exceptionHandler = newLabel()
            val skipHandler = newLabel()

            val tracePointLocal = newTracePointLocal()!!
            beforeMethodCall(opcode, owner, name, desc, tracePointLocal)
            if (name != "<init>") {
                // just hope that constructors do not throw exceptions.
                // we can not handle this case, because uninitialized values are not allowed by jvm
                visitTryCatchBlock(callStart, callEnd, exceptionHandler, null)
            }
            visitLabel(callStart)
            visitMethodInsn(opcode, owner, name, desc, itf)
            visitLabel(callEnd)
            afterMethodCall(Method(name, desc).returnType, tracePointLocal)

            goTo(skipHandler)
            visitLabel(exceptionHandler)
            onException(tracePointLocal)
            invokeAfterMethodCall(tracePointLocal) // notify strategy that the method finished
            throwException() // throw the exception further
            visitLabel(skipHandler)
        }

        // STACK: param_1 param_2 ... param_n
        private fun beforeMethodCall(opcode: Int, owner: String, methodName: String, desc: String, tracePointLocal: Int) {
            invokeBeforeMethodCall(methodName, tracePointLocal)
            captureParameters(opcode, owner, methodName, desc, tracePointLocal)
            captureOwnerName(opcode, owner, methodName, desc, tracePointLocal)
        }

        // STACK: returned value (unless void)
        private fun afterMethodCall(returnType: Type, tracePointLocal: Int) = adapter.run {
            if (returnType != Type.VOID_TYPE) {
                val returnedValue = newLocal(returnType)
                copyLocal(returnedValue)
                // initialize MethodCallCodePoint return value
                loadLocal(tracePointLocal)
                checkCast(METHOD_TRACE_POINT_TYPE)
                loadLocal(returnedValue)
                box(returnType)
                invokeVirtual(METHOD_TRACE_POINT_TYPE, INITIALIZE_RETURNED_VALUE_METHOD)
            }
            invokeAfterMethodCall(tracePointLocal)
        }

        // STACK: exception
        private fun onException(tracePointLocal: Int) = adapter.run {
            val exceptionLocal = newLocal(THROWABLE_TYPE)
            copyLocal(exceptionLocal)
            // initialize MethodCallCodePoint thrown exception
            loadLocal(tracePointLocal)
            checkCast(METHOD_TRACE_POINT_TYPE)
            loadLocal(exceptionLocal)
            invokeVirtual(METHOD_TRACE_POINT_TYPE, INITIALIZE_THROWN_EXCEPTION_METHOD)
        }

        // STACK: param_1 param_2 ... param_n
        private fun captureParameters(opcode: Int, owner: String, methodName: String, desc: String, tracePointLocal: Int) = adapter.run {
            val paramTypes = Type.getArgumentTypes(desc)
            if (paramTypes.isEmpty()) return // nothing to capture
            val params = copyParameters(paramTypes)
            val firstLoggedParameter = if (isAFUMethodCall(opcode, owner, methodName, desc)) {
                // do not log the first object in AFU methods
                1
            } else {
                0
            }
            val lastLoggedParameter = if (paramTypes.last().internalName == "kotlin/coroutines/Continuation" && isSuspend(owner, methodName, desc)) {
                // do not log the last continuation in suspend functions
                paramTypes.size - 1
            } else {
                paramTypes.size
            }
            // create array of parameters
            push(lastLoggedParameter - firstLoggedParameter) // size of the array
            visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
            val parameterValuesLocal = newLocal(OBJECT_ARRAY_TYPE)
            storeLocal(parameterValuesLocal)
            for (i in firstLoggedParameter until lastLoggedParameter) {
                loadLocal(parameterValuesLocal)
                push(i - firstLoggedParameter)
                loadLocal(params[i])
                box(paramTypes[i]) // in case it is a primitive type
                arrayStore(OBJECT_TYPE)
            }
            // initialize MethodCallCodePoint parameter values
            loadLocal(tracePointLocal)
            checkCast(METHOD_TRACE_POINT_TYPE)
            loadLocal(parameterValuesLocal)
            invokeVirtual(METHOD_TRACE_POINT_TYPE, INITIALIZE_PARAMETERS_METHOD)
        }

        // STACK: owner param_1 param_2 ... param_n
        private fun captureOwnerName(opcode: Int, owner: String, methodName: String, desc: String, tracePointLocal: Int) = adapter.run {
            if (!isAFUMethodCall(opcode, owner, methodName, desc)) {
                // currently object name labels are used only for AFUs
                return
            }
            val afuLocal = newLocal(Type.getType("L$owner;"))
            // temporarily remove parameters from stack to copy AFU
            val params = storeParameters(desc)
            copyLocal(afuLocal)
            // return parameters to the stack
            for (param in params)
                loadLocal(param)
            // initialize MethodCallCodePoint owner name
            loadLocal(tracePointLocal)
            checkCast(METHOD_TRACE_POINT_TYPE)
            // get afu name
            loadObjectManager()
            loadLocal(afuLocal)
            invokeVirtual(OBJECT_MANAGER_TYPE, GET_OBJECT_NAME)
            invokeVirtual(METHOD_TRACE_POINT_TYPE, INITIALIZE_OWNER_NAME_METHOD)
        }

        private fun invokeBeforeMethodCall(methodName: String, tracePointLocal: Int) {
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocationAndTracePoint(tracePointLocal, METHOD_TRACE_POINT_TYPE) { iThread, actorId, callStackTrace, ste ->
                MethodCallTracePoint(iThread, actorId, callStackTrace, methodName, ste)
            }
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_METHOD_CALL_METHOD)
        }

        private fun invokeAfterMethodCall(tracePointLocal: Int) {
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.loadLocal(tracePointLocal)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, AFTER_METHOD_CALL_METHOD)
        }

        private fun isInternalCoroutineCall(owner: String, name: String) =
            owner == "kotlin/coroutines/intrinsics/IntrinsicsKt" && name == "getCOROUTINE_SUSPENDED"
    }

    /**
     * Replaces `Unsafe.getUnsafe` with `UnsafeHolder.getUnsafe`, because
     * transformed java.util classes can not access Unsafe directly after transformation.
     */
    private class UnsafeTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            if (owner.isUnsafe() && name == "getUnsafe") {
                // load Unsafe
                adapter.push(owner.canonicalClassName)
                adapter.invokeStatic(UNSAFE_HOLDER_TYPE, GET_UNSAFE_METHOD)
                adapter.checkCast(Type.getType("L${owner};"))
                return
            }
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    /**
     * Tracks names of fields for created AFUs and saves them via ObjectManager.
     * CallStackTraceTransformer should be an earlier transformer than this transformer, because
     * this transformer reuse code locations created by CallStackTraceTransformer.
     */
    private inner class AFUTrackingTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, mname: String, desc: String, isInterface: Boolean) = adapter.run {
            val isAFUCreation = opcode == INVOKESTATIC && mname == "newUpdater" && isAFU(owner)
            when {
                isAFUCreation -> {
                    val nameLocal = newLocal(STRING_TYPE)
                    copyLocal(nameLocal) // name is the last parameter
                    visitMethodInsn(opcode, owner, mname, desc, isInterface)
                    val afuLocal = newLocal(Type.getType("L$owner;"))
                    copyLocal(afuLocal) // copy AFU
                    loadObjectManager()
                    loadLocal(afuLocal)
                    loadLocal(nameLocal)
                    invokeVirtual(OBJECT_MANAGER_TYPE, SET_OBJECT_NAME)
                }
                else -> visitMethodInsn(opcode, owner, mname, desc, isInterface)
            }
        }
    }

    /**
     * Replaces Object.hashCode and Any.hashCode invocations with just zero.
     * This transformer prevents non-determinism due to the native hashCode implementation,
     * which typically returns memory address of the object. There is no guarantee that
     * memory addresses will be the same in different runs.
     */
    private class HashCodeStubTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            val isAnyHashCodeInvocation = owner == "kotlin/Any" && name == "hashCode"
            val isObjectHashCodeInvocation = owner == "java/lang/Object" && name == "hashCode"
            if (isAnyHashCodeInvocation || isObjectHashCodeInvocation) {
                // instead of calling object.hashCode just return zero
                adapter.pop() // remove object from the stack
                adapter.push(0)
                return
            }
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    /**
     * Replaces `System.nanoTime` and `System.currentTimeMillis` with stubs to prevent non-determinism
     */
    private class TimeStubTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            if (owner == "java/lang/System" && (name == "nanoTime" || name == "currentTimeMillis")) {
                adapter.push(1337L) // any constant value
                return
            }
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    /**
     * Makes java.util.Random and all classes that extend it deterministic.
     * In every Random method invocation replaces the owner with Random from ManagedStateHolder.
     */
    private class RandomTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        private val randomMethods by lazy { Random::class.java.declaredMethods }

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            if (opcode == INVOKEVIRTUAL && extendsRandom(owner.canonicalClassName) && isRandomMethod(name, desc)) {
                val locals = adapter.storeParameters(desc)
                adapter.pop() // pop replaced Random
                loadRandom()
                adapter.loadLocals(locals)
                adapter.visitMethodInsn(opcode, "java/util/Random", name, desc, itf)
                return
            }
            // there are also static methods in ThreadLocalRandom that are used inside java.util.concurrent.
            // they are replaced with nextInt method.
            val isThreadLocalRandomMethod = owner == "java/util/concurrent/ThreadLocalRandom"
            val isStriped64Method = owner == "java/util/concurrent/atomic/Striped64"
            if (isThreadLocalRandomMethod && (name == "nextSecondarySeed" || name == "getProbe") ||
                isStriped64Method && name == "getProbe") {
                loadRandom()
                adapter.invokeVirtual(RANDOM_TYPE, NEXT_INT_METHOD)
                return
            }
            if ((isThreadLocalRandomMethod || isStriped64Method) && name == "advanceProbe") {
                adapter.pop() // pop parameter
                loadRandom()
                adapter.invokeVirtual(RANDOM_TYPE, NEXT_INT_METHOD)
                return
            }
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }

        private fun loadRandom() {
            adapter.getStatic(MANAGED_STATE_HOLDER_TYPE, ManagedStrategyStateHolder::random.name, RANDOM_TYPE)
        }

        private fun extendsRandom(className: String) = java.util.Random::class.java.isAssignableFrom(Class.forName(className))

        private fun isRandomMethod(methodName: String, desc: String): Boolean = randomMethods.any {
                val method = Method.getMethod(it)
                method.name == methodName && method.descriptor == desc
            }
    }

    /**
     * Removes all `Thread.yield` invocations, because model checking strategy manages the execution itself
     */
    private class ThreadYieldTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            if (opcode == INVOKESTATIC && owner == "java/lang/Thread" && name == "yield") return
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before monitorenter and monitorexit instructions
     */
    private inner class SynchronizedBlockTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                MONITORENTER -> {
                    val opEnd = newLabel()
                    val skipMonitorEnter: Label = newLabel()
                    dup()
                    invokeBeforeLockAcquire()
                    // check whether the lock should be really acquired
                    ifZCmp(GeneratorAdapter.EQ, skipMonitorEnter)
                    monitorEnter()
                    goTo(opEnd)
                    visitLabel(skipMonitorEnter)
                    pop()
                    visitLabel(opEnd)
                }
                MONITOREXIT -> {
                    val opEnd = newLabel()
                    val skipMonitorExit: Label = newLabel()
                    dup()
                    invokeBeforeLockRelease()
                    ifZCmp(GeneratorAdapter.EQ, skipMonitorExit)
                    // check whether the lock should be really released
                    monitorExit()
                    goTo(opEnd)
                    visitLabel(skipMonitorExit)
                    pop()
                    visitLabel(opEnd)
                }
                else -> visitInsn(opcode)
            }
        }

        // STACK: monitor
        private fun invokeBeforeLockAcquire() {
            invokeBeforeLockAcquireOrRelease(BEFORE_LOCK_ACQUIRE_METHOD, ::MonitorEnterTracePoint, MONITORENTER_TRACE_POINT_TYPE)
        }

        // STACK: monitor
        private fun invokeBeforeLockRelease() {
            invokeBeforeLockAcquireOrRelease(BEFORE_LOCK_RELEASE_METHOD, ::MonitorExitTracePoint, MONITOREXIT_TRACE_POINT_TYPE)
        }

        // STACK: monitor
        private fun invokeBeforeLockAcquireOrRelease(method: Method, codeLocationConstructor: CodeLocationTracePointConstructor, tracePointType: Type) {
            val monitorLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(monitorLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocationAndTracePoint(null, tracePointType, codeLocationConstructor)
            adapter.loadLocal(monitorLocal)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, method)
        }
    }

    /**
     * Replace "method(...) {...}" with "method(...) {synchronized(this) {...} }"
     */
    private inner class SynchronizedBlockAddingTransformer(methodName: String, mv: GeneratorAdapter, access: Int, private val classVersion: Int) : ManagedStrategyMethodVisitor(methodName, mv) {
        private val isStatic: Boolean = access and ACC_STATIC != 0
        private val tryLabel = Label()
        private val catchLabel = Label()

        override fun visitCode() = adapter.run {
            super.visitCode()
            loadSynchronizedMethodMonitorOwner()
            monitorEnter()
            // note that invoking monitorEnter here leads to unknown line number in the code location.
            // TODO: will invoking monitorEnter after the first visitLineNumber be correct?
            visitLabel(tryLabel)
        }

        override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
            visitLabel(catchLabel)
            loadSynchronizedMethodMonitorOwner()
            monitorExit()
            throwException()
            visitTryCatchBlock(tryLabel, catchLabel, catchLabel, null)
            visitMaxs(maxStack, maxLocals)
        }

        override fun visitInsn(opcode: Int) {
            when (opcode) {
                ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                    loadSynchronizedMethodMonitorOwner()
                    adapter.monitorExit()
                }
                else -> {
                }
            }
            adapter.visitInsn(opcode)
        }

        private fun loadSynchronizedMethodMonitorOwner() = adapter.run {
            if (isStatic) {
                val classType = Type.getType("L$className;")
                if (classVersion >= V1_5) {
                    visitLdcInsn(classType)
                } else {
                    visitLdcInsn(classType.className)
                    invokeStatic(CLASS_TYPE, CLASS_FOR_NAME_METHOD)
                }
            } else {
                loadThis()
            }
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before wait and after notify calls
     */
    private inner class WaitNotifyTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
            var skipWaitOrNotify = newLabel()
            val isWait = isWait(opcode, name, desc)
            val isNotify = isNotify(opcode, name, desc)
            if (isWait) {
                skipWaitOrNotify = newLabel()
                val withTimeout = desc != "()V"
                var lastArgument = 0
                var firstArgument = 0
                when (desc) {
                    "(J)V" -> {
                        firstArgument = newLocal(Type.LONG_TYPE)
                        storeLocal(firstArgument)
                    }
                    "(JI)V" -> {
                        lastArgument = newLocal(Type.INT_TYPE)
                        storeLocal(lastArgument)
                        firstArgument = newLocal(Type.LONG_TYPE)
                        storeLocal(firstArgument)
                    }
                }
                dup() // copy monitor
                invokeBeforeWait(withTimeout)
                val beforeWait: Label = newLabel()
                ifZCmp(GeneratorAdapter.GT, beforeWait)
                pop() // pop monitor
                goTo(skipWaitOrNotify)
                visitLabel(beforeWait)
                // restore popped arguments
                when (desc) {
                    "(J)V" -> loadLocal(firstArgument)
                    "(JI)V" -> {
                         loadLocal(firstArgument)
                         loadLocal(lastArgument)
                     }
                }
            }
            if (isNotify) {
                val notifyAll = name == "notifyAll"
                dup() // copy monitor
                invokeBeforeNotify(notifyAll)
                val beforeNotify = newLabel()
                ifZCmp(GeneratorAdapter.GT, beforeNotify)
                pop() // pop monitor
                goTo(skipWaitOrNotify)
                visitLabel(beforeNotify)
            }
            visitMethodInsn(opcode, owner, name, desc, itf)
            visitLabel(skipWaitOrNotify)
        }

        private fun isWait(opcode: Int, name: String, desc: String): Boolean {
            if (opcode == INVOKEVIRTUAL && name == "wait") {
                when (desc) {
                    "()V", "(J)V", "(JI)V" -> return true
                }
            }
            return false
        }

        private fun isNotify(opcode: Int, name: String, desc: String): Boolean {
            val isNotify = opcode == INVOKEVIRTUAL && name == "notify" && desc == "()V"
            val isNotifyAll = opcode == INVOKEVIRTUAL && name == "notifyAll" && desc == "()V"
            return isNotify || isNotifyAll
        }

        // STACK: monitor
        private fun invokeBeforeWait(withTimeout: Boolean) {
            invokeOnWaitOrNotify(BEFORE_WAIT_METHOD, withTimeout, ::WaitTracePoint, WAIT_TRACE_POINT_TYPE)
        }

        // STACK: monitor
        private fun invokeBeforeNotify(notifyAll: Boolean) {
            invokeOnWaitOrNotify(BEFORE_NOTIFY_METHOD, notifyAll, ::NotifyTracePoint, NOTIFY_TRACE_POINT_TYPE)
        }

        // STACK: monitor
        private fun invokeOnWaitOrNotify(method: Method, flag: Boolean, codeLocationConstructor: CodeLocationTracePointConstructor, tracePointType: Type) {
            val monitorLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(monitorLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocationAndTracePoint(null, tracePointType, codeLocationConstructor)
            adapter.loadLocal(monitorLocal)
            adapter.push(flag)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, method)
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before park and after unpark calls
     */
    private inner class ParkUnparkTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
            val beforePark: Label = newLabel()
            val afterPark: Label = newLabel()
            val isPark = owner.isUnsafe() && name == "park"
            if (isPark) {
                val withoutTimeoutBranch: Label = newLabel()
                val invokeBeforeParkEnd: Label = newLabel()
                dup2()
                push(0L)
                ifCmp(Type.LONG_TYPE, GeneratorAdapter.EQ, withoutTimeoutBranch)
                push(true)
                invokeBeforePark()
                goTo(invokeBeforeParkEnd)
                visitLabel(withoutTimeoutBranch)
                push(false)
                invokeBeforePark()
                visitLabel(invokeBeforeParkEnd)
                // check whether should really park 
                ifZCmp(GeneratorAdapter.GT, beforePark) // park if returned true
                // delete park params
                pop2() // time
                pop() // isAbsolute
                pop() // Unsafe
                goTo(afterPark)
            }
            visitLabel(beforePark)
            val isUnpark = owner.isUnsafe() && name == "unpark"
            var threadLocal = 0
            if (isUnpark) {
                dup()
                threadLocal = newLocal(OBJECT_TYPE)
                storeLocal(threadLocal)
            }
            visitMethodInsn(opcode, owner, name, desc, itf)
            visitLabel(afterPark)
            if (isUnpark) {
                loadLocal(threadLocal)
                invokeAfterUnpark()
            }
        }

        // STACK: withTimeout
        private fun invokeBeforePark() {
            val withTimeoutLocal: Int = adapter.newLocal(Type.BOOLEAN_TYPE)
            adapter.storeLocal(withTimeoutLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocationAndTracePoint(null, PARK_TRACE_POINT_TYPE, ::ParkTracePoint)
            adapter.loadLocal(withTimeoutLocal)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_PARK_METHOD)
        }

        // STACK: thread
        private fun invokeAfterUnpark() {
            val threadLocal: Int = adapter.newLocal(OBJECT_TYPE)
            adapter.storeLocal(threadLocal)
            loadStrategy()
            loadCurrentThreadNumber()
            loadNewCodeLocationAndTracePoint(null, UNPARK_TRACE_POINT_TYPE, ::UnparkTracePoint)
            adapter.loadLocal(threadLocal)
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, AFTER_UNPARK_METHOD)
        }
    }

    /**
     * Track local objects for odd switch points elimination.
     * A local object is an object that can be possible viewed only from one thread.
     */
    private inner class LocalObjectManagingTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            val isObjectCreation = opcode == INVOKESPECIAL && name == "<init>" && owner == "java/lang/Object"
            val isImpossibleToTransformPrimitive = isImpossibleToTransformApiClass(owner.canonicalClassName)
            val lowerCaseName = name.toLowerCase(Locale.US)
            val isPrimitiveWrite = isImpossibleToTransformPrimitive && WRITE_KEYWORDS.any { it in lowerCaseName }
            val isObjectPrimitiveWrite = isPrimitiveWrite && Type.getArgumentTypes(descriptor).lastOrNull()?.descriptor?.isNotPrimitiveType() ?: false

            when {
                isObjectCreation -> {
                    adapter.dup() // will be used for onNewLocalObject method
                    adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    invokeOnNewLocalObject()
                }
                isObjectPrimitiveWrite -> {
                    // the exact list of methods that should be matched here:
                    // Unsafe.put[Ordered]?Object[Volatile]?
                    // Unsafe.getAndSet
                    // Unsafe.compareAndSwapObject
                    // VarHandle.set[Volatile | Acquire | Opaque]?
                    // VarHandle.[weak]?CompareAndSet[Plain | Acquire | Release]?
                    // VarHandle.compareAndExchange[Acquire | Release]?
                    // VarHandle.getAndSet[Acquire | Release]?
                    // AtomicReferenceFieldUpdater.compareAndSet
                    // AtomicReferenceFieldUpdater.[lazy]?Set
                    // AtomicReferenceFieldUpdater.getAndSet

                    // all this methods have the field owner as the first argument and the written value as the last one
                    val params = adapter.copyParameters(descriptor)
                    adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    adapter.loadLocal(params.first())
                    adapter.loadLocal(params.last())
                    invokeAddDependency()
                }
                else -> adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }

        override fun visitIntInsn(opcode: Int, operand: Int) {
            adapter.visitIntInsn(opcode, operand)
            if (opcode == NEWARRAY) {
                adapter.dup()
                invokeOnNewLocalObject()
            }
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            adapter.visitTypeInsn(opcode, type)
            if (opcode == ANEWARRAY) {
                adapter.dup()
                invokeOnNewLocalObject()
            }
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
            val isNotPrimitiveType = desc.isNotPrimitiveType()
            val isFinalField = isFinalField(owner, name)
            if (isNotPrimitiveType) {
                when (opcode) {
                    PUTSTATIC -> {
                        adapter.dup()
                        invokeDeleteLocalObject()
                    }
                    PUTFIELD -> {
                        // we cannot invoke this method for final field, because an object may uninitialized yet
                        // will add dependency for final fields after <init> ends instead
                        if (!isFinalField) {
                            // owner, value
                            adapter.dup2() // owner, value, owner, value
                            invokeAddDependency() // owner, value
                        }
                    }
                }
            }
            super.visitFieldInsn(opcode, owner, name, desc)
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            val value: Int
            when (opcode) {
                AASTORE -> {
                    // array, index, value
                    value = newLocal(OBJECT_TYPE)
                    storeLocal(value) // array, index
                    dup2() // array, index, array, index
                    pop() // array, index, array
                    loadLocal(value) // array, index, array, value
                    invokeAddDependency() // array, index
                    loadLocal(value) // array, index, value
                }
                RETURN -> if (methodName == "<init>") {
                    // handle all final field added dependencies
                    val ownerType = Type.getObjectType(className)
                    for (field in getNonStaticFinalFields(className)) {
                        if (field.type.isPrimitive) continue
                        val fieldType = Type.getType(field.type)
                        loadThis() // owner
                        loadThis() // owner, owner
                        getField(ownerType, field.name, fieldType) // owner, value
                        invokeAddDependency()
                    }
                }
            }
            adapter.visitInsn(opcode)
        }

        // STACK: object
        private fun invokeOnNewLocalObject() {
            if (eliminateLocalObjects) {
                val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
                adapter.storeLocal(objectLocal)
                loadObjectManager()
                adapter.loadLocal(objectLocal)
                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, NEW_LOCAL_OBJECT_METHOD)
            } else {
                adapter.pop()
            }
        }

        // STACK: object
        private fun invokeDeleteLocalObject() {
            if (eliminateLocalObjects) {
                val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
                adapter.storeLocal(objectLocal)
                loadObjectManager()
                adapter.loadLocal(objectLocal)
                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, DELETE_LOCAL_OBJECT_METHOD)
            } else {
                adapter.pop()
            }
        }

        // STACK: owner, dependant
        private fun invokeAddDependency() {
            if (eliminateLocalObjects) {
                val ownerLocal: Int = adapter.newLocal(OBJECT_TYPE)
                val dependantLocal: Int = adapter.newLocal(OBJECT_TYPE)
                adapter.storeLocal(dependantLocal)
                adapter.storeLocal(ownerLocal)
                loadObjectManager()
                adapter.loadLocal(ownerLocal)
                adapter.loadLocal(dependantLocal)
                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, ADD_DEPENDENCY_METHOD)
            } else {
                repeat(2) { adapter.pop() }
            }
        }
    }

    private open inner class ManagedStrategyMethodVisitor(protected val methodName: String, val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        private var lineNumber = 0

        protected fun invokeBeforeIgnoredSectionEntering() {
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, ENTER_IGNORED_SECTION_METHOD)
        }

        protected fun invokeAfterIgnoredSectionLeaving() {
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, LEAVE_IGNORED_SECTION_METHOD)
        }

        protected fun invokeMakeStateRepresentation() {
            if (collectStateRepresentation) {
                loadStrategy()
                loadCurrentThreadNumber()
                adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, MAKE_STATE_REPRESENTATION_METHOD)
            }
        }

        protected fun loadStrategy() {
            adapter.getStatic(MANAGED_STATE_HOLDER_TYPE, ManagedStrategyStateHolder::strategy.name, MANAGED_STRATEGY_TYPE)
        }

        protected fun loadMemoryLocationLabeler() {
            adapter.getStatic(MANAGED_STATE_HOLDER_TYPE, ManagedStrategyStateHolder::memoryLocationLabeler.name, MEMORY_LOCATION_LABELER_TYPE)
        }

        protected fun loadObjectManager() {
            adapter.getStatic(MANAGED_STATE_HOLDER_TYPE, ManagedStrategyStateHolder::objectManager.name, OBJECT_MANAGER_TYPE)
        }

        protected fun loadCurrentThreadNumber() {
            loadStrategy()
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, CURRENT_THREAD_NUMBER_METHOD)
        }

        // STACK: (empty) -> code location, trace point
        protected fun loadNewCodeLocationAndTracePoint(tracePointLocal: Int?, tracePointType: Type, codeLocationConstructor: CodeLocationTracePointConstructor) {
            // push the codeLocation on stack
            adapter.push(codeLocationIdProvider.newId())
            // push the corresponding trace point
            if (constructTraceRepresentation) {
                // add constructor for the code location
                val className = className  // for capturing by value in lambda constructor
                val fileName = fileName
                val lineNumber = lineNumber
                tracePointConstructors.add { iThread, actorId, callStackTrace ->
                    val ste = StackTraceElement(className, methodName, fileName, lineNumber)
                    codeLocationConstructor(iThread, actorId, callStackTrace, ste)
                }
                // invoke the constructor
                loadStrategy()
                adapter.push(tracePointConstructors.lastIndex)
                adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, CREATE_TRACE_POINT_METHOD)
                adapter.checkCast(tracePointType)
                // the created trace point is stored to tracePointLocal
                if (tracePointLocal != null) adapter.copyLocal(tracePointLocal)
            } else {
                // null instead of trace point when should not construct trace
                adapter.push(null as Type?)
            }
        }

        protected fun newTracePointLocal(): Int? =
            if (constructTraceRepresentation) {
                val tracePointLocal = adapter.newLocal(TRACE_POINT_TYPE)
                // initialize codePointLocal, because otherwise transformed code such as
                // if (b) write(local, value)
                // if (b) read(local)
                // causes bytecode verification exception
                adapter.push(null as Type?)
                adapter.storeLocal(tracePointLocal)
                tracePointLocal
            } else {
                // code locations are not used without logging enabled, so just return null
                null
            }

        /**
         * Generated code is equal to
         * ```
         * strategy.enterIgnoredSection()
         * try {
         *     generated by `block` code
         * } finally {
         *     strategy.leaveIgnoredSection()
         * }
         * ```
         */
        protected fun runInIgnoredSection(block: () -> Unit) = adapter.run {
            val callStart = newLabel()
            val callEnd = newLabel()
            val exceptionHandler = newLabel()
            val skipHandler = newLabel()
            if (name != "<init>") {
                // just hope that constructors do not throw exceptions.
                // we can not handle this case, because uninitialized values are not allowed by jvm
                visitTryCatchBlock(callStart, callEnd, exceptionHandler, null)
            }
            invokeBeforeIgnoredSectionEntering()
            visitLabel(callStart)
            block()
            visitLabel(callEnd)
            invokeAfterIgnoredSectionLeaving()
            goTo(skipHandler)
            // upon exception leave ignored section
            visitLabel(exceptionHandler)
            invokeAfterIgnoredSectionLeaving()
            throwException() // throw the exception further
            visitLabel(skipHandler)
        }

        override fun visitLineNumber(line: Int, start: Label) {
            lineNumber = line
            super.visitLineNumber(line, start)
        }
    }
}

/**
 * The counter that helps to assign gradually increasing disjoint ids to different code locations
 */
internal class CodeLocationIdProvider {
    var lastId = -1 // the first id will be zero
        private set
    fun newId() = ++lastId
}

// By default `java.util` interfaces are not transformed, while classes are.
// Here are the exceptions to these rules.
// They were found with the help of the `findAllTransformationProblems` method.
internal val NOT_TRANSFORMED_JAVA_UTIL_CLASSES = setOf(
    "java/util/ServiceLoader", // can not be transformed because of access to `SecurityManager`
    "java/util/concurrent/TimeUnit", // many not transformed interfaces such as `java.util.concurrent.BlockingQueue` use it
    "java/util/OptionalDouble", // used by `java.util.stream.DoubleStream`. Is an immutable collection
    "java/util/OptionalLong",
    "java/util/OptionalInt",
    "java/util/Optional",
    "java/util/Locale", // is an immutable class too
    "java/util/Locale\$Category",
    "java/util/Locale\$FilteringMode",
    "java/util/Currency",
    "java/util/Date",
    "java/util/Calendar",
    "java/util/TimeZone",
    "java/util/DoubleSummaryStatistics", // this class is mutable, but `java.util.stream.DoubleStream` interface better be not transformed
    "java/util/LongSummaryStatistics",
    "java/util/IntSummaryStatistics",
    "java/util/Formatter",
    "java/util/stream/PipelineHelper",
    "java/util/Random", // will be thread safe after `RandomTransformer` transformation
    "java/util/concurrent/ThreadLocalRandom"
)
internal val TRANSFORMED_JAVA_UTIL_INTERFACES = setOf(
    "java/util/concurrent/CompletionStage", // because it uses `java.util.concurrent.CompletableFuture`
    "java/util/Observer", // uses `java.util.Observable`
    "java/util/concurrent/RejectedExecutionHandler",
    "java/util/concurrent/ForkJoinPool\$ForkJoinWorkerThreadFactory",
    "java/util/jar/Pack200\$Packer",
    "java/util/jar/Pack200\$Unpacker",
    "java/util/prefs/PreferencesFactory",
    "java/util/ResourceBundle\$CacheKeyReference",
    "java/util/prefs/PreferenceChangeListener",
    "java/util/prefs/NodeChangeListener",
    "java/util/logging/Filter",
    "java/util/spi/ResourceBundleControlProvider"
)

private val OBJECT_TYPE = Type.getType(Any::class.java)
private val THROWABLE_TYPE = Type.getType(java.lang.Throwable::class.java)
private val MANAGED_STATE_HOLDER_TYPE = Type.getType(ManagedStrategyStateHolder::class.java)
private val MANAGED_STRATEGY_TYPE = Type.getType(ManagedStrategy::class.java)
private val MEMORY_LOCATION_LABELER_TYPE = Type.getType(MemoryLocationLabeler::class.java)
private val OBJECT_MANAGER_TYPE = Type.getType(ObjectManager::class.java)
private val RANDOM_TYPE = Type.getType(Random::class.java)
private val UNSAFE_HOLDER_TYPE = Type.getType(UnsafeHolder::class.java)
private val STRING_TYPE = Type.getType(String::class.java)
private val CLASS_TYPE = Type.getType(Class::class.java)
private val OBJECT_ARRAY_TYPE = Type.getType("[" + OBJECT_TYPE.descriptor)
private val TRACE_POINT_TYPE = Type.getType(TracePoint::class.java)
private val WRITE_TRACE_POINT_TYPE = Type.getType(WriteTracePoint::class.java)
private val READ_TRACE_POINT_TYPE = Type.getType(ReadTracePoint::class.java)
private val METHOD_TRACE_POINT_TYPE = Type.getType(MethodCallTracePoint::class.java)
private val MONITORENTER_TRACE_POINT_TYPE = Type.getType(MonitorEnterTracePoint::class.java)
private val MONITOREXIT_TRACE_POINT_TYPE = Type.getType(MonitorExitTracePoint::class.java)
private val WAIT_TRACE_POINT_TYPE = Type.getType(WaitTracePoint::class.java)
private val NOTIFY_TRACE_POINT_TYPE = Type.getType(NotifyTracePoint::class.java)
private val PARK_TRACE_POINT_TYPE = Type.getType(ParkTracePoint::class.java)
private val UNPARK_TRACE_POINT_TYPE = Type.getType(UnparkTracePoint::class.java)

private val CURRENT_THREAD_NUMBER_METHOD = Method.getMethod(ManagedStrategy::currentThreadNumber.javaMethod)
private val BEFORE_SHARED_VARIABLE_READ_METHOD = Method.getMethod(ManagedStrategy::beforeSharedVariableRead.javaMethod)
private val BEFORE_SHARED_VARIABLE_WRITE_METHOD = Method.getMethod(ManagedStrategy::beforeSharedVariableWrite.javaMethod)
private val ON_SHARED_VARIABLE_READ_METHOD = Method.getMethod(ManagedStrategy::onSharedVariableRead.javaMethod)
private val ON_SHARED_VARIABLE_WRITE_METHOD = Method.getMethod(ManagedStrategy::onSharedVariableWrite.javaMethod)
private val ON_COMPARE_AND_SET_METHOD = Method.getMethod(ManagedStrategy::onCompareAndSet.javaMethod)
private val ON_GET_AND_ADD_METHOD = Method.getMethod(ManagedStrategy::onGetAndAdd.javaMethod)
private val ON_ADD_AND_GET_METHOD = Method.getMethod(ManagedStrategy::onAddAndGet.javaMethod)
private val BEFORE_LOCK_ACQUIRE_METHOD = Method.getMethod(ManagedStrategy::beforeLockAcquire.javaMethod)
private val BEFORE_LOCK_RELEASE_METHOD = Method.getMethod(ManagedStrategy::beforeLockRelease.javaMethod)
private val BEFORE_WAIT_METHOD = Method.getMethod(ManagedStrategy::beforeWait.javaMethod)
private val BEFORE_NOTIFY_METHOD = Method.getMethod(ManagedStrategy::beforeNotify.javaMethod)
private val BEFORE_PARK_METHOD = Method.getMethod(ManagedStrategy::beforePark.javaMethod)
private val AFTER_UNPARK_METHOD = Method.getMethod(ManagedStrategy::afterUnpark.javaMethod)
private val ENTER_IGNORED_SECTION_METHOD = Method.getMethod(ManagedStrategy::enterIgnoredSection.javaMethod)
private val LEAVE_IGNORED_SECTION_METHOD = Method.getMethod(ManagedStrategy::leaveIgnoredSection.javaMethod)
private val BEFORE_METHOD_CALL_METHOD = Method.getMethod(ManagedStrategy::beforeMethodCall.javaMethod)
private val AFTER_METHOD_CALL_METHOD = Method.getMethod(ManagedStrategy::afterMethodCall.javaMethod)
private val MAKE_STATE_REPRESENTATION_METHOD = Method.getMethod(ManagedStrategy::addStateRepresentation.javaMethod)
private val BEFORE_ATOMIC_METHOD_CALL_METHOD = Method.getMethod(ManagedStrategy::beforeAtomicMethodCall.javaMethod)
private val CREATE_TRACE_POINT_METHOD = Method.getMethod(ManagedStrategy::createTracePoint.javaMethod)
private val NEW_LOCAL_OBJECT_METHOD = Method.getMethod(ObjectManager::newLocalObject.javaMethod)
private val DELETE_LOCAL_OBJECT_METHOD = Method.getMethod(ObjectManager::deleteLocalObject.javaMethod)
private val IS_LOCAL_OBJECT_METHOD = Method.getMethod(ObjectManager::isLocalObject.javaMethod)
private val ADD_DEPENDENCY_METHOD = Method.getMethod(ObjectManager::addDependency.javaMethod)
private val SET_OBJECT_NAME = Method.getMethod(ObjectManager::setObjectName.javaMethod)
private val GET_OBJECT_NAME = Method.getMethod(ObjectManager::getObjectName.javaMethod)
private val GET_UNSAFE_METHOD = Method.getMethod(UnsafeHolder::getUnsafe.javaMethod)
private val CLASS_FOR_NAME_METHOD = Method("forName", CLASS_TYPE, arrayOf(STRING_TYPE)) // manual, because there are several forName methods
private val INITIALIZE_WRITTEN_VALUE_METHOD = Method.getMethod(WriteTracePoint::initializeWrittenValue.javaMethod)
private val INITIALIZE_READ_VALUE_METHOD = Method.getMethod(ReadTracePoint::initializeReadValue.javaMethod)
private val INITIALIZE_RETURNED_VALUE_METHOD = Method.getMethod(MethodCallTracePoint::initializeReturnedValue.javaMethod)
private val INITIALIZE_THROWN_EXCEPTION_METHOD = Method.getMethod(MethodCallTracePoint::initializeThrownException.javaMethod)
private val INITIALIZE_PARAMETERS_METHOD = Method.getMethod(MethodCallTracePoint::initializeParameters.javaMethod)
private val INITIALIZE_OWNER_NAME_METHOD = Method.getMethod(MethodCallTracePoint::initializeOwnerName.javaMethod)
private val NEXT_INT_METHOD = Method("nextInt", Type.INT_TYPE, emptyArray<Type>())
private val LABEL_STATIC_FIELD = Method.getMethod(MemoryLocationLabeler::labelStaticField.javaMethod)
private val LABEL_OBJECT_FIELD = Method.getMethod(MemoryLocationLabeler::labelObjectField.javaMethod)
private val LABEL_ARRAY_ELEMENT = Method.getMethod(MemoryLocationLabeler::labelArrayElement.javaMethod)
private val LABEL_ATOMIC_PRIMITIVE = Method.getMethod(MemoryLocationLabeler::labelAtomicPrimitive.javaMethod)

private val WRITE_KEYWORDS = listOf("set", "put", "swap", "exchange")

/**
 * Returns array of locals containing given parameters.
 * STACK: param_1 param_2 ... param_n
 * RESULT STACK: (empty)
 */
private fun GeneratorAdapter.storeParameters(paramTypes: Array<Type>): IntArray {
    val locals = IntArray(paramTypes.size)
    // store all arguments
    for (i in paramTypes.indices.reversed()) {
        locals[i] = newLocal(paramTypes[i])
        storeLocal(locals[i], paramTypes[i])
    }
    return locals
}

private fun GeneratorAdapter.storeParameters(methodDescriptor: String) = storeParameters(Type.getArgumentTypes(methodDescriptor))

private fun GeneratorAdapter.unboxOrCast(type: Type) {
    // Get rid of boxes
    if (type.isPrimitive())
        unbox(type)
    else
        checkCast(type)
}

/**
 * Returns array of locals containing given parameters.
 * STACK: param_1 param_2 ... param_n
 * RESULT STACK: param_1 param_2 ... param_n (the stack is not changed)
 */
private fun GeneratorAdapter.copyParameters(paramTypes: Array<Type>): IntArray {
    val locals = storeParameters(paramTypes)
    loadLocals(locals)
    return locals
}

private fun GeneratorAdapter.copyParameters(methodDescriptor: String): IntArray = copyParameters(Type.getArgumentTypes(methodDescriptor))

private fun GeneratorAdapter.loadLocals(locals: IntArray) {
    for (local in locals)
        loadLocal(local)
}

/**
 * Saves the top value on the stack without changing stack.
 */
private fun GeneratorAdapter.copyLocal(local: Int) {
    storeLocal(local)
    loadLocal(local)
}

/**
 * Get non-static final fields that belong to the class. Note that final fields of super classes won't be returned.
 */
private fun getNonStaticFinalFields(ownerInternal: String): List<Field> {
    var ownerInternal = ownerInternal
    if (ownerInternal.startsWith(REMAPPED_PACKAGE_INTERNAL_NAME)) {
        ownerInternal = ownerInternal.substring(REMAPPED_PACKAGE_INTERNAL_NAME.length)
    }
    return try {
        val clazz = Class.forName(ownerInternal.canonicalClassName)
        val fields = clazz.declaredFields
        Arrays.stream(fields)
            .filter { field: Field -> field.modifiers and (Modifier.FINAL or Modifier.STATIC) == Modifier.FINAL }
            .collect(Collectors.toList())
    } catch (e: ClassNotFoundException) {
        throw RuntimeException(e)
    }
}

private fun isFinalField(ownerInternal: String, fieldName: String): Boolean {
    var internalName = ownerInternal
    if (internalName.startsWith(REMAPPED_PACKAGE_INTERNAL_NAME)) {
        internalName = internalName.substring(REMAPPED_PACKAGE_INTERNAL_NAME.length)
    }
    return try {
        val clazz = Class.forName(internalName.canonicalClassName)
        val field = findField(clazz, fieldName) ?: throw NoSuchFieldException("No $fieldName in ${clazz.name}")
        field.modifiers and Modifier.FINAL == Modifier.FINAL
    } catch (e: ClassNotFoundException) {
        throw RuntimeException(e)
    } catch (e: NoSuchFieldException) {
        throw RuntimeException(e)
    }
}

private fun findField(clazz: Class<*>?, fieldName: String): Field? {
    if (clazz == null) return null
    val fields = clazz.declaredFields
    for (field in fields) if (field.name == fieldName) return field
    // No field found in this class.
    // Search in super class first, then in interfaces.
    findField(clazz.superclass, fieldName)?.let { return it }
    clazz.interfaces.forEach { iClass ->
        findField(iClass, fieldName)?.let { return it }
    }
    return null
}

private fun String.isNotPrimitiveType() = startsWith("L") || startsWith("[")

private fun isSuspend(owner: String, methodName: String, descriptor: String): Boolean =
    try {
        Class.forName(owner.canonicalClassName).kotlin.declaredFunctions.any {
            it.isSuspend && it.name == methodName && Method.getMethod(it.javaMethod).descriptor == descriptor
        }
    } catch (e: Throwable) {
        // kotlin reflection is not available for some functions
        false
    }

private fun isSuspendStateMachine(internalClassName: String): Boolean {
    // all named suspend functions extend kotlin.coroutines.jvm.internal.ContinuationImpl
    // it is internal, so check by name
    return Class.forName(internalClassName.canonicalClassName).superclass?.name == "kotlin.coroutines.jvm.internal.ContinuationImpl"
}

private fun isStrategyMethod(className: String) = className.startsWith("org/jetbrains/kotlinx/lincheck/strategy")

private fun isAFU(owner: String) = owner.startsWith("java/util/concurrent/atomic/Atomic") && owner.endsWith("FieldUpdater")

// returns true only the method is declared in this class and is not inherited
private fun isClassMethod(owner: String, methodName: String, desc: String): Boolean =
    Class.forName(owner.canonicalClassName).declaredMethods.any {
        val method = Method.getMethod(it)
        method.name == methodName && method.descriptor == desc
    }

private fun isAFUMethodCall(opcode: Int, owner: String, methodName: String, desc: String) =
    opcode == INVOKEVIRTUAL && isAFU(owner) && isClassMethod(owner, methodName, desc)

private fun String.isUnsafe() = this == "sun/misc/Unsafe" || this == "jdk/internal/misc/Unsafe"

/**
 * Some API classes cannot be transformed due to the [sun.reflect.CallerSensitive] annotation.
 */
internal fun isImpossibleToTransformApiClass(className: String) =
    className == "sun.misc.Unsafe" ||
        className == "jdk.internal.misc.Unsafe" ||
        className == "java.lang.invoke.VarHandle" ||
        className.startsWith("java.util.concurrent.atomic.Atomic") && className.endsWith("FieldUpdater")

/**
 * This class is used for getting the [sun.misc.Unsafe] or [jdk.internal.misc.Unsafe] instance.
 * We need it in some transformed classes from the `java.util.` package,
 * and it cannot be accessed directly after the transformation.
 */
internal object UnsafeHolder {
    @Volatile
    private var theUnsafe: Any? = null

    @JvmStatic
    fun getUnsafe(unsafeClass: String): Any {
        if (theUnsafe == null) {
            try {
                val f = Class.forName(unsafeClass).getDeclaredField("theUnsafe")
                f.isAccessible = true
                theUnsafe = f.get(null)
            } catch (e: Exception) {
                throw RuntimeException(wrapInvalidAccessFromUnnamedModuleExceptionWithDescription(e))
            }
        }
        return theUnsafe!!
    }
}

private fun Type.isPrimitive() = sort in Type.BOOLEAN..Type.DOUBLE


private val BOXED_BYTE_TYPE = Type.getObjectType("java/lang/Byte")
private val BOXED_BOOLEAN_TYPE = Type.getObjectType("java/lang/Boolean")
private val BOXED_SHORT_TYPE = Type.getObjectType("java/lang/Short")
private val BOXED_CHARACTER_TYPE = Type.getObjectType("java/lang/Character")
private val BOXED_INTEGER_TYPE = Type.getObjectType("java/lang/Integer")
private val BOXED_FLOAT_TYPE = Type.getObjectType("java/lang/Float")
private val BOXED_LONG_TYPE = Type.getObjectType("java/lang/Long")
private val BOXED_DOUBLE_TYPE = Type.getObjectType("java/lang/Double")

private fun Type.getBoxedType(): Type {
    return when (sort) {
        Type.BYTE -> BOXED_BYTE_TYPE
        Type.BOOLEAN -> BOXED_BOOLEAN_TYPE
        Type.SHORT -> BOXED_SHORT_TYPE
        Type.CHAR -> BOXED_CHARACTER_TYPE
        Type.INT -> BOXED_INTEGER_TYPE
        Type.FLOAT -> BOXED_FLOAT_TYPE
        Type.LONG -> BOXED_LONG_TYPE
        Type.DOUBLE -> BOXED_DOUBLE_TYPE
        else -> throw IllegalStateException()
    }
}