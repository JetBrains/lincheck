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

import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.util.UnsafeHolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


private typealias Id = Long

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
internal class ObjectIdentityHashCodeTracker {
    companion object {
        /**
         * Offset (in bytes) of identity hashcode in an object header.
         *
         * @see <a href="https://shipilev.net/jvm/anatomy-quarks/26-identity-hash-code/#_hashcode_storage">JVM Anatomy Quark #26: Identity Hash Code</a>
         */
        private const val IDENTITY_HASHCODE_OFFSET: Long = 1L
    }
    private val initialHashCodes = ConcurrentHashMap<Id, Int>()
    private val nextObjectId = AtomicLong(0)
    private val objectIdAdvances = ConcurrentHashMap<Id, Id>()

    /**
     * This method substitutes identity hash code of the object in its header with the initial (from the first test execution) identity hashcode.
     * @return id of the created object.
     */
    fun afterNewTrackedObjectCreation(obj: Any): Id {
        val currentObjectId = if (isInTraceDebuggerMode) getNextObjectId() else 0
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
    fun resetObjectIds() {
        if (!isInTraceDebuggerMode) return
        nextObjectId.set(0)
    }

    /**
     * Advances the current object id with the delta, associated with the old id [oldObjectId],
     * previously received with [getNextObjectId].
     * 
     * If for the given [oldObjectId] there is no saved `newObjectId`,
     * the function saves the current object id and associates it with the [oldObjectId].
     * On subsequent re-runs, when for the given [oldObjectId] there exists a saved `newObjectId`,
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
     * the last allocated object id [oldObjectId] can be saved, and after the computation,
     * the new last object id can be associated with it via a call `advanceCurrentObjectId(oldObjectId)`.
     * On subsequent re-runs, the cached computation will be skipped, but the
     * current object id will still be advanced by the required delta via a call to `advanceCurrentObjectId(oldId)`.
     */
    fun advanceCurrentObjectId(oldObjectId: Id) {
        if (!isInTraceDebuggerMode) return
        val newObjectId = nextObjectId.get()
        val existingAdvance = objectIdAdvances.putIfAbsent(oldObjectId, newObjectId)
        if (existingAdvance != null) {
            nextObjectId.set(existingAdvance)
        }
    }

    /**
     * @return id of the current object and increments the global counter.
     */
    fun getNextObjectId(): Id {
        if (!isInTraceDebuggerMode) return 0
        return nextObjectId.getAndIncrement()
    }

    /**
     * @return initial identity hashcode for the object with specified [objectId].
     * If this is first time function is called for this object, then provided [identityHashCode] is treated as initial.
     */
    private fun getInitialIdentityHashCode(objectId: Id, identityHashCode: Int): Int =
        initialHashCodes.getOrPut(objectId) { identityHashCode }
}
