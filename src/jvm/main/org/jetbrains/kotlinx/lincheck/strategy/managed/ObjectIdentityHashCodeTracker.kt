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

import org.jetbrains.kotlinx.lincheck.util.UnsafeHolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


private typealias Id = Long

/**
 * This class stores initial identity hashcodes of created objects.
 * When the program is rerun the firstly calculated hashcodes are left as is and used to
 * substitute new hashcodes via [sun.misc.Unsafe] in the object headers.
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
    @OptIn(ExperimentalStdlibApi::class)
    fun afterNewTrackedObjectCreation(obj: Any): Id {
        val currentObjectId = getNextObjectId()
        val initialIdentityHashCode = getInitialIdentityHashCode(
            currentObjectId,
            System.identityHashCode(obj)
        )
        // TODO: bizarre and crazy code below (might not work for all JVM implementations)
        UnsafeHolder.UNSAFE.putInt(obj, IDENTITY_HASHCODE_OFFSET, initialIdentityHashCode)

        return currentObjectId
    }

    /**
     * Resets ids numeration and starts them from 0.
     */
    fun resetObjectIds() {
        nextObjectId.set(0)
    }

    /**
     * Advances the current object id with the delta, associated with the old id {@code oldId},
     * previously received with {@code getNextObjectId}.
     * <p>
     * If for the given {@code oldId} there is no saved {@code newId},
     * the function saves the current object id and associates it with the {@code oldId}.
     * On subsequent re-runs, when for the given {@code oldId} there exists a saved {@code newId},
     * the function sets the counter to the {@code newId}.
     * <p>
     * This function is typically used to account for some cached computations:
     * on the first run the actual computation is performed and its result is cached,
     * and on subsequent runs the cached value is re-used.
     * One example of such a situation is the {@code invokedynamic} instruction.
     * <p>
     * In such cases, on the first run, the performed computation may allocate more objects,
     * assigning more object ids to them.
     * On subsequent runs, however, these objects will not be allocated, and thus the object ids numbering may vary.
     * To account for this, before the first invocation of the cached computation,
     * the last allocated object id {@code oldId} can be saved, and after the computation,
     * the new last object id can be associated with it via a call {@code advanceCurrentObjectId(oldId)}.
     * On subsequent re-runs, the cached computation will be skipped, but the
     * current object id will still be advanced by the required delta via a call to {@code advanceCurrentObjectId(oldId)}.
     */
    fun advanceCurrentObjectId(oldObjectId: Id) {
        val newObjectId = nextObjectId.get()
        val existingAdvance = objectIdAdvances.putIfAbsent(oldObjectId, newObjectId)
        if (existingAdvance != null) {
            nextObjectId.set(existingAdvance)
        }
    }

    /**
     * @return id of current object and increments the global counter.
     */
    fun getNextObjectId(): Id = nextObjectId.getAndIncrement()

    /**
     * @return initial identity hashcode for object with specified [objectId].
     * If this is first time function is called for this object, then provided [identityHashCode] is treated as initial.
     */
    private fun getInitialIdentityHashCode(objectId: Id, identityHashCode: Int): Int =
        initialHashCodes.getOrPut(objectId) { identityHashCode }
}
