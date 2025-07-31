/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.lincheck.descriptors.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import kotlin.math.max

/**
 * ASM method visitor adapter that tracks owner names (access paths)
 * for values on the operand stack and in local variables during bytecode analysis.
 *
 * This adapter simulates JVM execution to maintain detailed ownership chains for:
 * - local variable accesses;
 * - field accesses (static and instance);
 * - array operations (element access and length);
 * - stack manipulations.
 *
 * The owner names are used by Lincheck to assign meaningful names to
 * receiver objects on field and method accesses.
 *
 * The implementation is based on [org.objectweb.asm.commons.AnalyzerAdapter].
 *
 * @param owner the owner class name being analyzed.
 * @param access method access flags (see [Opcodes]).
 * @param name method name.
 * @param descriptor method signature descriptor (see [Type]).
 * @param methodVisitor optional delegate method visitor.
 * @param methodVariables provides active variable information.
 *
 * @see org.objectweb.asm.commons.AnalyzerAdapter
 */
class OwnerNameAnalyzerAdapter protected constructor(
    api: Int,
    // The owner's class name.
    private val owner: String?,
    access: Int,
    name: String?,
    descriptor: String,
    methodVisitor: MethodVisitor?,
    val methodVariables: MethodVariables,
) : MethodVisitor(api, methodVisitor) {
    /**
     * Tracks [OwnerName]-s for objects stored in local variables.
     */
    var locals: MutableList<OwnerName?>?

    /**
     * Tracks [OwnerName]-s for objects stored on the stack.
     */
    var stack: MutableList<OwnerName?>?

    /** The maximum stack size of this method.  */
    private var maxStack = 0

    /** The maximum number of local variables of this method.  */
    private var maxLocals: Int

    /**
     * Constructs a new [OwnerNameAnalyzerAdapter]. *Subclasses must not use this constructor*.
     * Instead, they must use the [.AnalyzerAdapter] version.
     *
     * @param owner the owner's class name.
     * @param access the method's access flags (see [Opcodes]).
     * @param name the method's name.
     * @param descriptor the method's descriptor (see [Type]).
     * @param methodVisitor the method visitor to which this adapter delegates calls. May be null.
     */
    constructor(
        owner: String?,
        access: Int,
        name: String?,
        descriptor: String,
        methodVisitor: MethodVisitor?,
        methodVariables: MethodVariables,
    ) : this( /* latest api = */Opcodes.ASM9, owner, access, name, descriptor, methodVisitor, methodVariables)

    /**
     * Constructs a new [OwnerNameAnalyzerAdapter].
     *
     * @param api the ASM API version implemented by this visitor. Must be one of the `ASM`*x* values in [Opcodes].
     * @param owner the owner's class name.
     * @param access the method's access flags (see [Opcodes]).
     * @param name the method's name.
     * @param descriptor the method's descriptor (see [Type]).
     * @param methodVisitor the method visitor to which this adapter delegates calls. May be null.
     */
    init {
        locals = mutableListOf<OwnerName?>()
        stack = mutableListOf<OwnerName?>()

        val isStatic = (access and Opcodes.ACC_STATIC != 0)
        val argumentTypes = Type.getArgumentTypes(descriptor)!!
        val localsTypes = if (isStatic) argumentTypes else arrayOf(OBJECT_TYPE) + argumentTypes

        @Suppress("UNCHECKED_CAST")
        initializeLocals(localsTypes.size, localsTypes as Array<Any?>)
        maxLocals = localsTypes.size
    }

    override fun visitLabel(label: Label?) {
        super.visitLabel(label)
        val activeVars = methodVariables.getActiveVars()
        setActiveLocalVariableNames(activeVars)
    }

    override fun visitFrame(
        type: Int,
        numLocal: Int,
        local: Array<Any?>,
        numStack: Int,
        stack: Array<Any?>
    ) {
        require(type == Opcodes.F_NEW) {
            "OwnerNameAnalyzerAdapter only accepts expanded frames (see ClassReader.EXPAND_FRAMES)"
        }

        super.visitFrame(type, numLocal, local, numStack, stack)

        if (this.locals != null) {
            this.locals!!.clear()
            this.stack!!.clear()
        } else {
            this.locals = mutableListOf()
            this.stack = mutableListOf()
        }

        initializeLocals(numLocal, local)
        initializeStack(numStack, stack)

        maxLocals = max(maxLocals, this.locals!!.size)
        maxStack = max(maxStack, this.stack!!.size)
    }

    override fun visitInsn(opcode: Int) {
        super.visitInsn(opcode)
        execute(opcode, 0, null)
        if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
            this.locals = null
            this.stack = null
        }
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        super.visitIntInsn(opcode, operand)
        execute(opcode, operand, null)
    }

    override fun visitVarInsn(opcode: Int, varIndex: Int) {
        super.visitVarInsn(opcode, varIndex)
        val stackSlotSize = if (opcode == Opcodes.RET) 1 else getLocalVarAccessOpcodeType(opcode).size
        maxLocals = max(maxLocals, varIndex + stackSlotSize)
        execute(opcode, varIndex, null)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        super.visitTypeInsn(opcode, type)
        execute(opcode, 0, type)
    }

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String) {
        super.visitFieldInsn(opcode, owner, name, descriptor)
        execute(opcode, 0, className = owner?.toCanonicalClassName(), fieldName = name, descriptor = descriptor)
    }

    override fun visitMethodInsn(
        opcodeAndSource: Int,
        owner: String?,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        if (api < Opcodes.ASM5 && (opcodeAndSource and Opcodes.SOURCE_DEPRECATED) == 0) {
            // Redirect the call to the deprecated version of this method.
            super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface)
            return
        }
        super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface)
        val opcode = opcodeAndSource and Opcodes.SOURCE_MASK.inv()

        if (this.locals == null) {
            return
        }

        val returnType = Type.getReturnType(descriptor)
        pop(descriptor)
        if (opcode != Opcodes.INVOKESTATIC) {
            pop()
        }
        push(returnType.size)
    }

    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String,
        bootstrapMethodHandle: Handle?,
        vararg bootstrapMethodArguments: Any?
    ) {
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        if (this.locals == null) {
            return
        }

        val returnType = Type.getReturnType(descriptor)
        pop(descriptor)
        push(returnType.size)
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) {
        super.visitJumpInsn(opcode, label)
        execute(opcode, 0, null)
        if (opcode == Opcodes.GOTO) {
            this.locals = null
            this.stack = null
        }
    }

    override fun visitLdcInsn(value: Any?) {
        super.visitLdcInsn(value)
        if (this.locals == null) {
            return
        }
        // TODO: need to investigate how to lookup constant name (if available)
        when (value) {
            is Int -> {
                push(null) // TODO
            }
            is Long -> {
                push(null) // TODO
                push(null) // TODO
            }
            is Float -> {
                push(null) // TODO
            }
            is Double -> {
                push(null) // TODO
                push(null) // TODO
            }
            is String -> {
                push(null) // TODO
            }
            is Type -> {
                val sort = value.sort
                when (sort) {
                    Type.OBJECT, Type.ARRAY -> {
                        push(null) // TODO
                    }
                    Type.METHOD -> {
                        push(null) // TODO
                    }
                    else -> throw IllegalArgumentException()
                }
            }
            is Handle -> {
                push(null) // TODO
            }
            is ConstantDynamic -> {
                push(null)
                // TODO: adding constant name should be fine
                // push(value.name)
            }
            else -> throw IllegalArgumentException()
        }
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
        super.visitIincInsn(varIndex, increment)
        maxLocals = max(maxLocals, varIndex + 1)
        execute(Opcodes.IINC, varIndex, null)
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {
        super.visitTableSwitchInsn(min, max, dflt, *labels)
        execute(Opcodes.TABLESWITCH, 0, null)
        this.locals = null
        this.stack = null
    }

    override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<Label?>?) {
        super.visitLookupSwitchInsn(dflt, keys, labels)
        execute(Opcodes.LOOKUPSWITCH, 0, null)
        this.locals = null
        this.stack = null
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
        execute(Opcodes.MULTIANEWARRAY, numDimensions, descriptor)
    }

    override fun visitLocalVariable(
        name: String?,
        descriptor: String,
        signature: String?,
        start: Label?,
        end: Label?,
        index: Int
    ) {
        val firstDescriptorChar = descriptor[0]
        val stackSlotSize = (if (firstDescriptorChar == 'J' || firstDescriptorChar == 'D') 2 else 1)
        maxLocals = max(maxLocals, index + stackSlotSize)
        super.visitLocalVariable(name, descriptor, signature, start, end, index)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        if (mv != null) {
            this.maxStack = max(this.maxStack, maxStack)
            this.maxLocals = max(this.maxLocals, maxLocals)
            mv.visitMaxs(this.maxStack, this.maxLocals)
        }
    }

    // -----------------------------------------------------------------------------------------------

    private fun get(local: Int): OwnerName? {
        maxLocals = max(maxLocals, local + 1)
        return if (local < locals!!.size) locals!![local] else null
    }

    private fun set(local: Int, ownerName: OwnerName?) {
        maxLocals = max(maxLocals, local + 1)
        while (local >= locals!!.size) {
            locals!!.add(null)
        }
        locals!![local] = ownerName
    }

    private fun push(ownerName: OwnerName?) {
        stack!!.add(ownerName)
        maxStack = max(maxStack, stack!!.size)
    }

    private fun push(numSlots: Int) {
        for (i in 0 until numSlots) {
            stack!!.add(null)
        }
        maxStack = max(maxStack, stack!!.size)
    }

    private fun pop(): OwnerName? {
        return stack!!.removeAt(stack!!.size - 1)
    }

    private fun pop(numSlots: Int) {
        val size = stack!!.size
        val end = size - numSlots
        for (i in size - 1 downTo end) {
            stack!!.removeAt(i)
        }
    }

    private fun pop(descriptor: String) {
        val firstDescriptorChar = descriptor[0]
        if (firstDescriptorChar == '(') {
            var numSlots = 0
            val types = Type.getArgumentTypes(descriptor)
            for (type in types) {
                numSlots += type.size
            }
            pop(numSlots)
        } else if (firstDescriptorChar == 'J' || firstDescriptorChar == 'D') {
            pop(2)
        } else {
            pop(1)
        }
    }

    private fun execute(
        opcode: Int,
        intArg: Int,
        className: String? = null,
        fieldName: String? = null,
        descriptor: String? = null
    ) {
        require(!(opcode == Opcodes.JSR || opcode == Opcodes.RET)) { "JSR/RET are not supported" }
        if (this.locals == null) {
            return
        }
        val value1: Any?
        val value2: Any?
        val value3: Any?
        val value4: Any?
        when (opcode) {

            /* Local variable access instructions */

            Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.FLOAD -> {
                val localVarName = methodVariables.getActiveVar(intArg)
                if (localVarName != null) {
                    val localVarAccess = LocalVariableAccess(localVarName)
                    push(OwnerName(localVarAccess))
                } else {
                    push(null)
                }
            }

            Opcodes.LLOAD, Opcodes.DLOAD -> {
                val localVarName = methodVariables.getActiveVar(intArg)
                if (localVarName != null) {
                    val localVarAccess = LocalVariableAccess(localVarName)
                    push(OwnerName(localVarAccess))
                    push(null)
                } else {
                    push(null)
                    push(null)
                }
            }

            Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.FSTORE -> {
                pop()
            }

            Opcodes.LSTORE, Opcodes.DSTORE -> {
                pop(2)
            }

            /* Field access instructions */

            Opcodes.GETSTATIC -> {
                val fieldAccess = StaticFieldAccess(className!!, fieldName!!)
                push(OwnerName(fieldAccess))
            }

            Opcodes.PUTSTATIC -> {
                pop(descriptor!!)
            }

            Opcodes.GETFIELD -> {
                val ownerName = pop()
                val fieldAccess = ObjectFieldAccess(className!!, fieldName!!)
                if (ownerName != null) {
                    push(ownerName.concatenate(fieldAccess))
                } else {
                    push(null)
                }
            }

            Opcodes.PUTFIELD -> {
                pop(descriptor!!)
                pop()
            }

            /* Array access instructions */
            Opcodes.AALOAD,
            Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
            Opcodes.FALOAD, Opcodes.DALOAD -> {
                val indexName = pop()
                val arrayName = pop()
                val arrayAccess = indexName?.let { ArrayElementByNameAccess(it) }
                if (arrayAccess != null && arrayName != null) {
                    push(arrayName.concatenate(arrayAccess))
                } else {
                    push(null)
                }
                if (getArrayAccessOpcodeType(opcode).size == 2) {
                    push(null)
                }
            }

            Opcodes.IASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE, Opcodes.FASTORE, Opcodes.AASTORE -> {
                pop(3)
            }

            Opcodes.LASTORE, Opcodes.DASTORE -> {
                pop(4)
            }

            Opcodes.ARRAYLENGTH -> {
                val arrayName = pop()
                val arrayLengthAccess = ArrayLengthAccess
                if (arrayName != null) {
                    push(arrayName.concatenate(arrayLengthAccess))
                } else {
                    push(null)
                }
            }

            /* New object creation */

            Opcodes.NEW -> {
                push(null)
            }

            Opcodes.NEWARRAY -> {
                pop()
                push(null)
            }

            Opcodes.ANEWARRAY -> {
                pop()
                push(null)
            }

            Opcodes.MULTIANEWARRAY -> {
                pop(intArg)
                push(null)
            }

            /* Type casts */

            Opcodes.INSTANCEOF -> {
                pop()
                push(null)
            }

            Opcodes.CHECKCAST -> {
                value1 = pop()
                push(value1)
            }

            /* Constants */

            Opcodes.ACONST_NULL -> {
                push(null)
            }

            Opcodes.ICONST_M1,
            Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
            Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5,
            Opcodes.BIPUSH, Opcodes.SIPUSH -> {
                push(null)
            }

            Opcodes.LCONST_0, Opcodes.LCONST_1 -> {
                push(null)
                push(null)
            }

            Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> {
                push(null)
            }

            Opcodes.DCONST_0, Opcodes.DCONST_1 -> {
                push(null)
                push(null)
            }

            /* Control-flow */

            Opcodes.NOP, Opcodes.GOTO, Opcodes.RETURN
                -> {}

            Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.ARETURN, Opcodes.ATHROW -> {
                pop(1)
            }

            Opcodes.LRETURN, Opcodes.DRETURN -> {
                pop(2)
            }

            Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
            Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                pop(1)
            }

            Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> {
                pop(1)
            }

            Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
            Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
            Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                pop(2)
            }

            Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> {
                pop(1)
            }

            /* Stack manipulation */

            Opcodes.POP -> {
                pop(1)
            }

            Opcodes.POP2 -> {
                pop(2)
            }

            Opcodes.DUP -> {
                value1 = pop()
                push(value1)
                push(value1)
            }

            Opcodes.DUP_X1 -> {
                value1 = pop()
                value2 = pop()
                push(value1)
                push(value2)
                push(value1)
            }

            Opcodes.DUP_X2 -> {
                value1 = pop()
                value2 = pop()
                value3 = pop()
                push(value1)
                push(value3)
                push(value2)
                push(value1)
            }

            Opcodes.DUP2 -> {
                value1 = pop()
                value2 = pop()
                push(value2)
                push(value1)
                push(value2)
                push(value1)
            }

            Opcodes.DUP2_X1 -> {
                value1 = pop()
                value2 = pop()
                value3 = pop()
                push(value2)
                push(value1)
                push(value3)
                push(value2)
                push(value1)
            }

            Opcodes.DUP2_X2 -> {
                value1 = pop()
                value2 = pop()
                value3 = pop()
                value4 = pop()
                push(value2)
                push(value1)
                push(value4)
                push(value3)
                push(value2)
                push(value1)
            }

            Opcodes.SWAP -> {
                value1 = pop()
                value2 = pop()
                push(value1)
                push(value2)
            }

            /* Arithmetic operations */

            Opcodes.INEG, Opcodes.FNEG -> {
                pop(1)
                push(null)
            }

            Opcodes.LNEG, Opcodes.DNEG -> {
                pop(2)
                push(null)
                push(null)
            }

            Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
            Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR -> {
                pop(2)
                push(null)
            }

            Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
            Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR -> {
                pop(4)
                push(null)
                push(null)
            }

            Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM -> {
                pop(2)
                push(null)
            }

            Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> {
                pop(4)
                push(null)
                push(null)
            }

            Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> {
                pop(3)
                push(null)
                push(null)
            }

            Opcodes.IINC
                -> {}

            /* Comparison operations */

            Opcodes.FCMPL, Opcodes.FCMPG -> {
                pop(2)
                push(null)
            }

            Opcodes.LCMP, Opcodes.DCMPL, Opcodes.DCMPG -> {
                pop(4)
                push(null)
            }

            /* Numeric type conversions */

            Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> {
                pop(1)
                push(null)
            }

            Opcodes.I2F, Opcodes.F2I -> {
                pop(1)
                push(null)
            }

            Opcodes.I2L, Opcodes.F2L -> {
                pop(1)
                push(null)
                push(null)
            }

            Opcodes.I2D, Opcodes.F2D -> {
                pop(1)
                push(null)
                push(null)
            }

            Opcodes.L2I, Opcodes.D2I -> {
                pop(2)
                push(null)
            }

            Opcodes.L2F, Opcodes.D2F -> {
                pop(2)
                push(null)
            }

            Opcodes.L2D, Opcodes.D2L -> {
                pop(2)
                push(null)
                push(null)
            }

            /* Other */

            else -> throw IllegalArgumentException("Invalid opcode " + opcode)
        }
    }

    private fun setActiveLocalVariableNames(localVariables: List<LocalVariableInfo>) {
        if (this.locals == null) return
        for (i in this.locals!!.indices) {
            val localVarName = localVariables.find { it.index == i }?.name ?: continue
            val localVarAccess = LocalVariableAccess(localVarName)
            locals!![i] = OwnerName(localVarAccess)
        }
    }

    private fun initializeLocals(numLocals: Int, localsTypes: Array<Any?> /* , localVariables: List<LocalVariableInfo> */) {
        for (i in 0 ..< numLocals) {
            val localType = localsTypes[i]
            locals!!.add(null)
            if (localType == Opcodes.LONG || localType == Opcodes.DOUBLE) {
                locals!!.add(null)
            }
        }
    }

    private fun initializeStack(numTypes: Int, types: Array<Any?>) {
        for (i in 0 ..< numTypes) {
            val type = types[i]
            stack!!.add(null)
            if (type == Opcodes.LONG || type == Opcodes.DOUBLE) {
                stack!!.add(null)
            }
        }
    }
}