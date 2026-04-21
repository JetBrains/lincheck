/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.util.toBoolean
import org.jetbrains.lincheck.util.toInt
import sun.nio.ch.lincheck.TestThread
import java.lang.ref.WeakReference


internal class EventStructureObjectTracker(private val eventStructure: EventStructure): BaseObjectTracker() {

    override val shouldTrackImmutableValues: Boolean = true

    // We have two types of object entries.
    // * Internal entries which correspond to objects that are allocated during a test invocation,
    //   as well as the test  instance itself.
    //   These are purged from the ObjectTracker after each invocation.
    //   Also when replaying an invocation, the ObjectID of an internal object is set to be equal to the ObjectID
    //   of the previous allocation event.
    // * External which belong to objects that were already allocated before we started the tests
    //   Most often these are the initial threads, and the initial values of the fields of the test instance class
    //   Since these values are persistent across invocations, we keep them in the object tracker after invocations
    //   We also hold a reference to them to prevent the GC from removing them, while they are still in use
    private class EventStructureObjectEntry(
        objNumber: Int,
        objHashCode: Int,
        objDisplayNumber: Int,
        objWeakReference: WeakReference<Any>,
        objStrongReference: Any?,
        objKind: ObjectKind,
        val allocation: AtomicThreadEvent,
    ) : BaseObjectEntry(
        objNumber,
        objHashCode,
        objDisplayNumber,
        objWeakReference,
        objStrongReference,
        objKind,
    ) {}


    private var initEvent: AtomicThreadEvent? = null

    fun initialize(initEvent: AtomicThreadEvent) {
        require(initEvent.label is InitializationLabel)
        this.initEvent = initEvent
    }

    //NOTE: as in the oringinal ObjectRegistry, if an external object is already registered
    // we do not register it again.
    // This is because the external object may already exist, which messes up the objectIDs
    override fun registerExternalObject(obj: Any): ObjectEntry =
       get(obj) ?: super.registerExternalObject(obj)


    override fun createObjectEntry(
        objNumber: Int,
        objHashCode: Int,
        objDisplayNumber: Int,
        obj: Any,
        kind: ObjectKind
    ): ObjectEntry {
        // We create a base entry just for the weak reference inside it
        val objWeakReference = createWeakReference(obj)
        if (kind == ObjectKind.EXTERNAL) {
            (initEvent!!.label as InitializationLabel).trackExternalObject(obj.javaClass.simpleName, objNumber)
            return EventStructureObjectEntry(
                objNumber,
                objHashCode,
                objDisplayNumber,
                objWeakReference,
                objStrongReference = obj,
                objKind = kind,
                initEvent!!,
            )
        } else {
            val iThread = (Thread.currentThread() as? TestThread)?.threadId ?: eventStructure.mainThreadId
            // We create a new object allocation event and we "suggest" an objNumber
            // If we are in the replay phase however, the object allocation may have a different id
            // In that case we just take the id from the object allocation
            val allocationEvent = eventStructure.addObjectAllocationEvent(iThread!!,obj.opaque(), objNumber)
            val actualObjectNumber = (allocationEvent.label as ObjectAllocationLabel).objectID
            check(actualObjectNumber <= objNumber)
            return EventStructureObjectEntry(
                actualObjectNumber,
                objHashCode,
                objDisplayNumber,
                objWeakReference,
                objStrongReference = obj,
                objKind = kind,
                allocationEvent,
            )
        }
    }

    private fun getEventStructureEntry(id: ObjectNumber): EventStructureObjectEntry? {
        return lookupByNumber(id) as? EventStructureObjectEntry
    }

    fun getAllocation(id: ObjectNumber) : AtomicThreadEvent? {
        if (id == STATIC_OBJECT_NUMBER) return initEvent
        return getEventStructureEntry(id)?.allocation
    }

    fun getObject(id: ObjectNumber): OpaqueValue? {
        return lookupByNumber(id)?.objectWeakReference?.get()?.opaque()
    }

    override fun reset() {
        retain { (it as? EventStructureObjectEntry)?.objKind == ObjectKind.EXTERNAL }
    }
}

internal fun EventStructureObjectTracker.registerValueIfAbsent(obj: OpaqueValue?): ObjectNumber =
    when {
        obj == null -> NULL_OBJECT_NUMBER
        else -> registerObjectIfAbsent(obj.unwrap()).objectNumber
    }

internal fun EventStructureObjectTracker.getValue(type: Types.Type, id: ValueID): OpaqueValue? = when (type) {
    Types.LONG_TYPE       -> id.opaque()
    Types.INT_TYPE        -> id.toInt().opaque()
    Types.BYTE_TYPE       -> id.toByte().opaque()
    Types.SHORT_TYPE      -> id.toShort().opaque()
    Types.CHAR_TYPE       -> id.toInt().toChar().opaque()
    Types.BOOLEAN_TYPE    -> id.toInt().toBoolean().opaque()
    Types.LONG_TYPE_BOXED     -> id.opaque()
    Types.INT_TYPE_BOXED      -> id.toInt().opaque()
    Types.BYTE_TYPE_BOXED     -> id.toByte().opaque()
    Types.SHORT_TYPE_BOXED    -> id.toShort().opaque()
    Types.CHAR_TYPE_BOXED     -> id.toInt().toChar().opaque()
    Types.BOOLEAN_TYPE_BOXED  -> id.toInt().toBoolean().opaque()
    else                -> getObject(id.toInt())
}

internal fun EventStructureObjectTracker.getOrRegisterValueID(type: Types.Type, value: OpaqueValue?): ValueID {
    if (value == null) return NULL_OBJECT_NUMBER.toLong()
    return when (type) {
        Types.LONG_TYPE       -> (value.unwrap() as Long)
        Types.INT_TYPE        -> (value.unwrap() as Int).toLong()
        Types.SHORT_TYPE      -> (value.unwrap() as Short).toLong()
        Types.CHAR_TYPE       -> (value.unwrap() as Char).code.toLong()
        // sometimes, due to JVM internals, boolean values can be reinterpreted as byte values
        // (e.g., because of BALOAD and BASTORE instructions are used for both boolean and byte arrays);
        // thus if the type-cast failed, we try to reinterpret the value and cast it to manually
        Types.BYTE_TYPE       ->
            (value.unwrap() as? Byte)?.toLong() ?:
            (value.unwrap() as Boolean).toInt().toLong()
        Types.BOOLEAN_TYPE    ->
            (value.unwrap() as? Boolean)?.toInt()?.toLong() ?:
            (value.unwrap() as Byte).toBoolean().toInt().toLong()
        Types.LONG_TYPE_BOXED     -> (value.unwrap() as Long)
        Types.INT_TYPE_BOXED      -> (value.unwrap() as Int).toLong()
        Types.BYTE_TYPE_BOXED     -> (value.unwrap() as Byte).toLong()
        Types.SHORT_TYPE_BOXED    -> (value.unwrap() as Short).toLong()
        Types.CHAR_TYPE_BOXED     -> (value.unwrap() as Char).code.toLong()
        Types.BOOLEAN_TYPE_BOXED  -> (value.unwrap() as Boolean).toInt().toLong()
        else                -> registerValueIfAbsent(value).toLong()
    }
}