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
    methodVisitor: MethodVisitor?
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
    var locals: MutableList<Any?>?

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
    var stack: MutableList<Any?>?

    /** The labels that designate the next instruction to be visited. May be null.  */
    private var labels: MutableList<Label?>? = null

    /**
     * TODO: update the documentation
     *
     * The uninitialized types in the current execution frame. This map associates internal names to
     * Label objects (see [Type.getInternalName]). Each label designates a NEW instruction
     * that created the currently uninitialized types, and the associated internal name represents the
     * NEW operand, i.e. the final, initialized type value.
     */
    var uninitializedTypes: MutableMap<Any?, Any?>

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
        methodVisitor: MethodVisitor?
    ) : this( /* latest api = */Opcodes.ASM9, owner, access, name, descriptor, methodVisitor)

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
        locals = ArrayList<Any?>()
        stack = ArrayList<Any?>()
        uninitializedTypes = HashMap<Any?, Any?>()

        if ((access and Opcodes.ACC_STATIC) == 0) {
            if ("<init>" == name) {
                locals!!.add(Opcodes.UNINITIALIZED_THIS)
            } else {
                locals!!.add(owner)
            }
        }
        for (argumentType in Type.getArgumentTypes(descriptor)) {
            when (argumentType.getSort()) {
                Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> locals!!.add(Opcodes.INTEGER)
                Type.FLOAT -> locals!!.add(Opcodes.FLOAT)
                Type.LONG -> {
                    locals!!.add(Opcodes.LONG)
                    locals!!.add(Opcodes.TOP)
                }

                Type.DOUBLE -> {
                    locals!!.add(Opcodes.DOUBLE)
                    locals!!.add(Opcodes.TOP)
                }

                Type.ARRAY -> locals!!.add(argumentType.getDescriptor())
                Type.OBJECT -> locals!!.add(argumentType.getInternalName())
                else -> throw AssertionError()
            }
        }
        maxLocals = locals!!.size
    }

    override fun visitFrame(
        type: Int,
        numLocal: Int,
        local: Array<Any?>,
        numStack: Int,
        stack: Array<Any?>
    ) {
        require(type == Opcodes.F_NEW) { "AnalyzerAdapter only accepts expanded frames (see ClassReader.EXPAND_FRAMES)" }

        super.visitFrame(type, numLocal, local, numStack, stack)

        if (this.locals != null) {
            this.locals!!.clear()
            this.stack!!.clear()
        } else {
            this.locals = ArrayList<Any?>()
            this.stack = ArrayList<Any?>()
        }
        Companion.visitFrameTypes(numLocal, local, this.locals!!)
        Companion.visitFrameTypes(numStack, stack, this.stack!!)
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
        val isLongOrDouble =
            opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD || opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE
        maxLocals = max(maxLocals, varIndex + (if (isLongOrDouble) 2 else 1))
        execute(opcode, varIndex, null)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        if (opcode == Opcodes.NEW) {
            if (labels == null) {
                val label = Label()
                labels = ArrayList<Label?>(3)
                labels!!.add(label)
                if (mv != null) {
                    mv.visitLabel(label)
                }
            }
            for (label in labels!!) {
                uninitializedTypes.put(label, type)
            }
        }
        super.visitTypeInsn(opcode, type)
        execute(opcode, 0, type)
    }

    override fun visitFieldInsn(
        opcode: Int, owner: String?, name: String?, descriptor: String
    ) {
        super.visitFieldInsn(opcode, owner, name, descriptor)
        execute(opcode, 0, descriptor)
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
            labels = null
            return
        }
        pop(descriptor)
        if (opcode != Opcodes.INVOKESTATIC) {
            val value = pop()
            if (opcode == Opcodes.INVOKESPECIAL && name == "<init>") {
                val initializedValue: Any?
                if (value === Opcodes.UNINITIALIZED_THIS) {
                    initializedValue = this.owner
                } else {
                    initializedValue = owner
                }
                for (i in locals!!.indices) {
                    if (locals!![i] === value) {
                        locals!![i] = initializedValue
                    }
                }
                for (i in stack!!.indices) {
                    if (stack!![i] === value) {
                        stack!![i] = initializedValue
                    }
                }
            }
        }
        pushDescriptor(descriptor)
        labels = null
    }

    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String,
        bootstrapMethodHandle: Handle?,
        vararg bootstrapMethodArguments: Any?
    ) {
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        if (this.locals == null) {
            labels = null
            return
        }
        pop(descriptor)
        pushDescriptor(descriptor)
        labels = null
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) {
        super.visitJumpInsn(opcode, label)
        execute(opcode, 0, null)
        if (opcode == Opcodes.GOTO) {
            this.locals = null
            this.stack = null
        }
    }

    override fun visitLabel(label: Label?) {
        super.visitLabel(label)
        if (labels == null) {
            labels = ArrayList<Label?>(3)
        }
        labels!!.add(label)
    }

    override fun visitLdcInsn(value: Any?) {
        super.visitLdcInsn(value)
        if (this.locals == null) {
            labels = null
            return
        }
        if (value is Int) {
            push(Opcodes.INTEGER)
        } else if (value is Long) {
            push(Opcodes.LONG)
            push(Opcodes.TOP)
        } else if (value is Float) {
            push(Opcodes.FLOAT)
        } else if (value is Double) {
            push(Opcodes.DOUBLE)
            push(Opcodes.TOP)
        } else if (value is String) {
            push("java/lang/String")
        } else if (value is Type) {
            val sort = value.sort
            if (sort == Type.OBJECT || sort == Type.ARRAY) {
                push("java/lang/Class")
            } else if (sort == Type.METHOD) {
                push("java/lang/invoke/MethodType")
            } else {
                throw IllegalArgumentException()
            }
        } else if (value is Handle) {
            push("java/lang/invoke/MethodHandle")
        } else if (value is ConstantDynamic) {
            pushDescriptor(value.descriptor)
        } else {
            throw IllegalArgumentException()
        }
        labels = null
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
        maxLocals = max(
            maxLocals, index + (if (firstDescriptorChar == 'J' || firstDescriptorChar == 'D') 2 else 1)
        )
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
    private fun get(local: Int): Any? {
        maxLocals = max(maxLocals, local + 1)
        return if (local < locals!!.size) locals!![local] else Opcodes.TOP
    }

    private fun set(local: Int, type: Any?) {
        maxLocals = max(maxLocals, local + 1)
        while (local >= locals!!.size) {
            locals!!.add(Opcodes.TOP)
        }
        locals!!.set(local, type)
    }

    private fun push(type: Any?) {
        stack!!.add(type)
        maxStack = max(maxStack, stack!!.size)
    }

    private fun pushDescriptor(fieldOrMethodDescriptor: String?) {
        val descriptor =
            if (fieldOrMethodDescriptor?.get(0) == '(')
                Type.getReturnType(fieldOrMethodDescriptor).descriptor
            else
                fieldOrMethodDescriptor
        when (descriptor?.get(0)) {
            'V' -> return
            'Z', 'C', 'B', 'S', 'I' -> {
                push(Opcodes.INTEGER)
                return
            }

            'F' -> {
                push(Opcodes.FLOAT)
                return
            }

            'J' -> {
                push(Opcodes.LONG)
                push(Opcodes.TOP)
                return
            }

            'D' -> {
                push(Opcodes.DOUBLE)
                push(Opcodes.TOP)
                return
            }

            '[' -> push(descriptor)
            'L' -> push(descriptor.substring(1, descriptor.length - 1))
            else -> throw AssertionError()
        }
    }

    private fun pop(): Any? {
        return stack!!.removeAt(stack!!.size - 1)
    }

    private fun pop(numSlots: Int) {
        val size = stack!!.size
        val end = size - numSlots
        for (i in size - 1 downTo end) {
            stack!!.removeAt(i)
        }
    }

    private fun pop(descriptor: String?) {
        val firstDescriptorChar = descriptor?.get(0)
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

    private fun execute(opcode: Int, intArg: Int, stringArg: String?) {
        require(!(opcode == Opcodes.JSR || opcode == Opcodes.RET)) { "JSR/RET are not supported" }
        if (this.locals == null) {
            labels = null
            return
        }
        val value1: Any?
        val value2: Any?
        val value3: Any?
        val t4: Any?
        when (opcode) {
            Opcodes.NOP, Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.GOTO, Opcodes.RETURN -> {}
            Opcodes.ACONST_NULL -> push(Opcodes.NULL)
            Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH -> push(
                Opcodes.INTEGER
            )

            Opcodes.LCONST_0, Opcodes.LCONST_1 -> {
                push(Opcodes.LONG)
                push(Opcodes.TOP)
            }

            Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> push(Opcodes.FLOAT)
            Opcodes.DCONST_0, Opcodes.DCONST_1 -> {
                push(Opcodes.DOUBLE)
                push(Opcodes.TOP)
            }

            Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> push(get(intArg))
            Opcodes.LLOAD, Opcodes.DLOAD -> {
                push(get(intArg))
                push(Opcodes.TOP)
            }

            Opcodes.LALOAD, Opcodes.D2L -> {
                pop(2)
                push(Opcodes.LONG)
                push(Opcodes.TOP)
            }

            Opcodes.DALOAD, Opcodes.L2D -> {
                pop(2)
                push(Opcodes.DOUBLE)
                push(Opcodes.TOP)
            }

            Opcodes.AALOAD -> {
                pop(1)
                value1 = pop()
                if (value1 is String) {
                    pushDescriptor(value1.substring(1))
                } else if (value1 === Opcodes.NULL) {
                    push(value1)
                } else {
                    push("java/lang/Object")
                }
            }

            Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> {
                value1 = pop()
                set(intArg, value1)
                if (intArg > 0) {
                    value2 = get(intArg - 1)
                    if (value2 === Opcodes.LONG || value2 === Opcodes.DOUBLE) {
                        set(intArg - 1, Opcodes.TOP)
                    }
                }
            }

            Opcodes.LSTORE, Opcodes.DSTORE -> {
                pop(1)
                value1 = pop()
                set(intArg, value1)
                set(intArg + 1, Opcodes.TOP)
                if (intArg > 0) {
                    value2 = get(intArg - 1)
                    if (value2 === Opcodes.LONG || value2 === Opcodes.DOUBLE) {
                        set(intArg - 1, Opcodes.TOP)
                    }
                }
            }

            Opcodes.IASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE, Opcodes.FASTORE, Opcodes.AASTORE -> pop(
                3
            )

            Opcodes.LASTORE, Opcodes.DASTORE -> pop(4)
            Opcodes.POP, Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE, Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.ARETURN, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH, Opcodes.ATHROW, Opcodes.MONITORENTER, Opcodes.MONITOREXIT, Opcodes.IFNULL, Opcodes.IFNONNULL -> pop(
                1
            )

            Opcodes.POP2, Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.LRETURN, Opcodes.DRETURN -> pop(
                2
            )

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
                t4 = pop()
                push(value2)
                push(value1)
                push(t4)
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

            Opcodes.IALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD, Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.L2I, Opcodes.D2I, Opcodes.FCMPL, Opcodes.FCMPG -> {
                pop(2)
                push(Opcodes.INTEGER)
            }

            Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR -> {
                pop(4)
                push(Opcodes.LONG)
                push(Opcodes.TOP)
            }

            Opcodes.FALOAD, Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM, Opcodes.L2F, Opcodes.D2F -> {
                pop(2)
                push(Opcodes.FLOAT)
            }

            Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> {
                pop(4)
                push(Opcodes.DOUBLE)
                push(Opcodes.TOP)
            }

            Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> {
                pop(3)
                push(Opcodes.LONG)
                push(Opcodes.TOP)
            }

            Opcodes.IINC -> set(intArg, Opcodes.INTEGER)
            Opcodes.I2L, Opcodes.F2L -> {
                pop(1)
                push(Opcodes.LONG)
                push(Opcodes.TOP)
            }

            Opcodes.I2F -> {
                pop(1)
                push(Opcodes.FLOAT)
            }

            Opcodes.I2D, Opcodes.F2D -> {
                pop(1)
                push(Opcodes.DOUBLE)
                push(Opcodes.TOP)
            }

            Opcodes.F2I, Opcodes.ARRAYLENGTH, Opcodes.INSTANCEOF -> {
                pop(1)
                push(Opcodes.INTEGER)
            }

            Opcodes.LCMP, Opcodes.DCMPL, Opcodes.DCMPG -> {
                pop(4)
                push(Opcodes.INTEGER)
            }

            Opcodes.GETSTATIC -> pushDescriptor(stringArg)
            Opcodes.PUTSTATIC -> pop(stringArg)
            Opcodes.GETFIELD -> {
                pop(1)
                pushDescriptor(stringArg)
            }

            Opcodes.PUTFIELD -> {
                pop(stringArg)
                pop()
            }

            Opcodes.NEW -> push(labels!!.get(0))
            Opcodes.NEWARRAY -> {
                pop()
                when (intArg) {
                    Opcodes.T_BOOLEAN -> pushDescriptor("[Z")
                    Opcodes.T_CHAR -> pushDescriptor("[C")
                    Opcodes.T_BYTE -> pushDescriptor("[B")
                    Opcodes.T_SHORT -> pushDescriptor("[S")
                    Opcodes.T_INT -> pushDescriptor("[I")
                    Opcodes.T_FLOAT -> pushDescriptor("[F")
                    Opcodes.T_DOUBLE -> pushDescriptor("[D")
                    Opcodes.T_LONG -> pushDescriptor("[J")
                    else -> throw IllegalArgumentException("Invalid array type " + intArg)
                }
            }

            Opcodes.ANEWARRAY -> {
                pop()
                pushDescriptor("[" + Type.getObjectType(stringArg))
            }

            Opcodes.CHECKCAST -> {
                pop()
                pushDescriptor(Type.getObjectType(stringArg).getDescriptor())
            }

            Opcodes.MULTIANEWARRAY -> {
                pop(intArg)
                pushDescriptor(stringArg)
            }

            else -> throw IllegalArgumentException("Invalid opcode " + opcode)
        }
        labels = null
    }

    companion object {
        private fun visitFrameTypes(
            numTypes: Int, frameTypes: Array<Any?>, result: MutableList<Any?>
        ) {
            for (i in 0..<numTypes) {
                val frameType = frameTypes[i]
                result.add(frameType)
                if (frameType === Opcodes.LONG || frameType === Opcodes.DOUBLE) {
                    result.add(Opcodes.TOP)
                }
            }
        }
    }
}