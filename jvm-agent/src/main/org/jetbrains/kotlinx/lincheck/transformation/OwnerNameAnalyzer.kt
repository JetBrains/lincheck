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

import org.objectweb.asm.*
import kotlin.math.max

/**
 * TODO: update the documentation
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
     * TODO: update the documentation
     *
     * The local variable slots for the current execution frame. Primitive types are represented by
     * [Opcodes.TOP], [Opcodes.INTEGER], [Opcodes.FLOAT], [Opcodes.LONG],
     * [Opcodes.DOUBLE],[Opcodes.NULL] or [Opcodes.UNINITIALIZED_THIS] (long and
     * double are represented by two elements, the second one being TOP). Reference types are
     * represented by String objects (representing internal names, see [ ][Type.getInternalName]), and uninitialized types by Label objects (this label designates the
     * NEW instruction that created this uninitialized value). This field is null for
     * unreachable instructions.
     */
    var locals: MutableList<String?>?

    /**
     * TODO: update the documentation
     *
     * The operand stack slots for the current execution frame. Primitive types are represented by
     * [Opcodes.TOP], [Opcodes.INTEGER], [Opcodes.FLOAT], [Opcodes.LONG],
     * [Opcodes.DOUBLE],[Opcodes.NULL] or [Opcodes.UNINITIALIZED_THIS] (long and
     * double are represented by two elements, the second one being TOP). Reference types are
     * represented by String objects (representing internal names, see [ ][Type.getInternalName]), and uninitialized types by Label objects (this label designates the
     * NEW instruction that created this uninitialized value). This field is null for
     * unreachable instructions.
     */
    var stack: MutableList<String?>?

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
        locals = mutableListOf<String?>()
        stack = mutableListOf<String?>()

        val isStatic = (access and Opcodes.ACC_STATIC == 0)
        val argumentTypes = Type.getArgumentTypes(descriptor)
        maxLocals = (if (isStatic) 0 else 1) + argumentTypes.size
    }

    override fun visitLabel(label: Label?) {
        super.visitLabel(label)
        if (this.locals != null) {
            this.locals!!.clear()
            this.stack!!.clear()
        } else {
            this.locals = mutableListOf()
            this.stack = mutableListOf()
        }

        val activeVars = methodVariables.getActiveVars()
        setActiveLocalVariableNames(activeVars)

        maxLocals = max(maxLocals, this.locals!!.size)
        maxStack = max(maxStack, this.stack!!.size)
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

        val activeVars = methodVariables.getActiveVars()
        check(activeVars.size == numLocal) {
            """
                Unexpected number of active local variables: ${activeVars.size}. 
                Number of local variables declared in the frame: $numLocal.
            """.trimIndent()
        }

        setActiveLocalVariableNames(activeVars)
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
        val stackSlotSize = if (opcode == Opcodes.RET) 1 else getVarInsnOpcodeType(opcode).stackSlotSize
        maxLocals = max(maxLocals, varIndex + stackSlotSize)
        execute(opcode, varIndex, null)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        super.visitTypeInsn(opcode, type)
        execute(opcode, 0, type)
    }

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String) {
        super.visitFieldInsn(opcode, owner, name, descriptor)
        execute(opcode, 0, fieldName = name, descriptor = descriptor)
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
        if (this.locals == null) {
            return
        }
        pop(descriptor)
        push(null)
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
        pop(descriptor)
        push(null)
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
                push(value.name) // TODO: this one should be fine
            }
            else -> throw IllegalArgumentException()
        }
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
        super.visitIincInsn(varIndex, increment)
        maxLocals = max(maxLocals, varIndex + 1)
        execute(Opcodes.IINC, varIndex, null)
    }

    override fun visitTableSwitchInsn(
        min: Int, max: Int, dflt: Label?, vararg labels: Label?
    ) {
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

    private fun get(local: Int): String? {
        maxLocals = max(maxLocals, local + 1)
        return if (local < locals!!.size) locals!![local] else null
    }

    private fun set(local: Int, ownerName: String?) {
        maxLocals = max(maxLocals, local + 1)
        while (local >= locals!!.size) {
            locals!!.add(null)
        }
        locals!![local] = ownerName
    }

    private fun push(ownerName: String?) {
        stack!!.add(ownerName)
        maxStack = max(maxStack, stack!!.size)
    }

    private fun pop(): String? {
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

    private fun execute(opcode: Int, intArg: Int, fieldName: String? = null, descriptor: String? = null) {
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
                push(localVarName)
            }

            Opcodes.LLOAD, Opcodes.DLOAD -> {
                val localVarName = methodVariables.getActiveVar(intArg)
                push(localVarName)
                push(null)
            }

            Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.FSTORE -> {
                pop()
            }

            Opcodes.LSTORE, Opcodes.DSTORE -> {
                pop(2)
            }

            /* Field access instructions */

            Opcodes.GETSTATIC -> {
                push(fieldName)
            }

            Opcodes.PUTSTATIC -> {
                pop(descriptor!!)
            }

            Opcodes.GETFIELD -> {
                pop(1) // TODO: should we use the object name?
                push(fieldName)
            }

            Opcodes.PUTFIELD -> {
                pop(descriptor!!)
                pop()
            }

            /* Array access instructions */

            Opcodes.AALOAD, Opcodes.IALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD, Opcodes.FALOAD -> {
                // TODO: can we do better?
                pop(2)
                push(null)
            }

            Opcodes.LALOAD, Opcodes.DALOAD -> {
                // TODO: can we do better?
                pop(2)
                push(null)
                push(null)
            }

            Opcodes.IASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE, Opcodes.FASTORE, Opcodes.AASTORE -> {
                pop(3)
            }

            Opcodes.LASTORE, Opcodes.DASTORE -> {
                pop(4)
            }

            Opcodes.ARRAYLENGTH -> {
                // TODO: can we do better?
                push(null)
            }

            /* New object creation */

            Opcodes.NEW -> {
                push(null)
            }

            Opcodes.NEWARRAY -> {
                pop()
                when (intArg) {
                    Opcodes.T_BOOLEAN, Opcodes.T_BYTE, Opcodes.T_CHAR,
                    Opcodes.T_SHORT, Opcodes.T_INT, Opcodes.T_FLOAT -> {
                        push(null)
                    }

                    Opcodes.T_DOUBLE, Opcodes.T_LONG -> {
                        push(null)
                        push(null)
                    }

                    else -> throw IllegalArgumentException("Invalid array type " + intArg)
                }
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

            Opcodes.MONITORENTER, Opcodes.MONITOREXIT, -> {
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
        for (i in localVariables.indices) {
            locals!!.add(localVariables[i].name)
            if (localVariables[i].type.stackSlotSize == 2) {
                locals!!.add(null)
            }
        }
    }

    private fun initializeStack(numTypes: Int, types: Array<Any?>) {
        for (i in 0 ..< numTypes) {
            val type = types[i]
            stack!!.add(null)
            if (type === Opcodes.LONG || type === Opcodes.DOUBLE) {
                stack!!.add(null)
            }
        }
    }
}