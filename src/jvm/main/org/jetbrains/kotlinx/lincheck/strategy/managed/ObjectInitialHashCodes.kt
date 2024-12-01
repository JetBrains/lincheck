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
internal class ObjectInitialHashCodes {
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
    fun onNewTrackedObjectCreation(obj: Any): Id {
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
     * Ensures that for the same old id, previously received with {@code getNextObjectId},
     * the counter after calling the function persists.
     * <p>
     * If for given {@code oldId} there is no saved {@code newId}, the function saves the current value.
     * If for given {@code oldId} there is a saved {@code newId}, the function sets the counter to the {@code newId}.
     */
    fun advanceCurrentObjectIdWithKnownOldObjectId(oldObjectId: Id) {
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
