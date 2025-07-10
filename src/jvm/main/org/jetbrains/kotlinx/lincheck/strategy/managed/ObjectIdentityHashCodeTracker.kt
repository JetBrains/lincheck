/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.lincheck.util.UnsafeHolder
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * This class stores initial identity hash-codes of created objects.
 * When the program is rerun the firstly calculated hash-codes are left as is and used to
 * substitute new hash-codes via [sun.misc.Unsafe] in the object headers.
 * 
 * In Lincheck mode all objects have identity hash code 0 to temporarily mitigate complexity
 * created by iterating over interleavings.
 *
 * To guarantee correct work, ensure that replays are deterministic.
 */
internal class ObjectIdentityHashCodeTracker: AbstractTraceDebuggerEventTracker {
    companion object {
        /**
         * Offset (in bytes) of identity hashcode in an object header.
         *
         * The memory layout of object header mark work from [2] (on 64 bits architectures):
         *
         * | unused:25 hash:31 -->| unused:1   age:4    biased_lock:1 lock:2 (normal object) |
         *
         * So the hash code starts after the 1st byte (1 unused + 4 age bits + 3 lock bits = 8 bits).
         *
         * Links:
         *   [1] JVM Anatomy Quark #26: Identity Hash Code:
         *   <a href="https://shipilev.net/jvm/anatomy-quarks/26-identity-hash-code/#_hashcode_storage">JVM Anatomy Quark #26: Identity Hash Code</a>
         *
         *   [2] OpenJDK object header source code:
         *   <a href="https://hg.openjdk.org/jdk8/jdk8/hotspot/file/87ee5ee27509/src/share/vm/oops/markOop.hpp">
         *
         * TODO:
         *   1. Check on 32 bits architectures.
         *   2. Re-check if CAS is needed to account for concurrent GC.
         *   3. Re-check wrt. compact identity hash codes:
         *      https://wiki.openjdk.org/display/lilliput/Compact+Identity+Hashcode
         */
        private const val IDENTITY_HASHCODE_OFFSET: Long = 1L
    }
    private val initialHashCodes = ConcurrentHashMap<TraceDebuggerEventId, Int>()
    private val nextObjectId = AtomicLong(0)
    private val objectIdAdvances = ConcurrentHashMap<TraceDebuggerEventId, TraceDebuggerEventId>()

    /**
     * This method substitutes identity hash code of the object in its header with the initial (from the first test execution) identity hashcode.
     * @return id of the created object.
     */
    fun afterNewTrackedObjectCreation(obj: Any): TraceDebuggerEventId {
        val currentObjectId = if (isInTraceDebuggerMode) getNextId() else 0
        val initialIdentityHashCode = getInitialIdentityHashCode(
            objectId = currentObjectId,
            identityHashCode = if (isInTraceDebuggerMode) System.identityHashCode(obj) else 0
        )
        // ATTENTION: bizarre and crazy code below (might not work for all JVM implementations)
        UnsafeHolder.UNSAFE.putInt(obj, IDENTITY_HASHCODE_OFFSET, initialIdentityHashCode)
        return currentObjectId
    }

    /**
     * Resets ids numeration and starts them from 0.
     */
    override fun resetIds() {
        if (!isInTraceDebuggerMode) return
        nextObjectId.set(0)
    }

    /**
     * Advances the current object id with the delta, associated with the old id [oldId],
     * previously received with [getNextId].
     * 
     * If for the given [oldId] there is no saved `newObjectId`,
     * the function saves the current object id and associates it with the [oldId].
     * On subsequent re-runs, when for the given [oldId] there exists a saved `newObjectId`,
     * the function sets the counter to the `newObjectId`.
     * 
     * This function is typically used to account for some cached computations:
     * on the first run the actual computation is performed and its result is cached,
     * and on subsequent runs the cached value is re-used.
     * One example of such a situation is the `invokedynamic` instruction.
     * 
     * In such cases, on the first run, the performed computation may allocate more objects,
     * assigning more object ids to them.
     * On subsequent runs, however, these objects will not be allocated, and thus the object ids numbering may vary.
     * To account for this, before the first invocation of the cached computation,
     * the last allocated object id [oldId] can be saved, and after the computation,
     * the new last object id can be associated with it via a call `advanceCurrentObjectId(oldObjectId)`.
     * On subsequent re-runs, the cached computation will be skipped, but the
     * current object id will still be advanced by the required delta via a call to `advanceCurrentObjectId(oldId)`.
     */
    override fun advanceCurrentId(oldId: TraceDebuggerEventId) {
        if (!isInTraceDebuggerMode) return
        val newObjectId = nextObjectId.get()
        val existingAdvance = objectIdAdvances.putIfAbsent(oldId, newObjectId)
        if (existingAdvance != null) {
            nextObjectId.set(existingAdvance)
        }
    }

    /**
     * @return id of the current object and increments the global counter.
     */
    override fun getNextId(): TraceDebuggerEventId {
        if (!isInTraceDebuggerMode) return 0
        return nextObjectId.getAndIncrement()
    }

    /**
     * @return initial identity hashcode for the object with specified [objectId].
     * If this is first time function is called for this object, then provided [identityHashCode] is treated as initial.
     */
    private fun getInitialIdentityHashCode(objectId: TraceDebuggerEventId, identityHashCode: Int): Int =
        initialHashCodes.getOrPut(objectId) { identityHashCode }
    
    override fun close() {
        initialHashCodes.clear()
        objectIdAdvances.clear()
    }
}
