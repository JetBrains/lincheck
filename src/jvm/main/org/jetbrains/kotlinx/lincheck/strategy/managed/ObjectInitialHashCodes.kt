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
import java.util.concurrent.atomic.AtomicInteger

/**
 * This class stores initial identity hashcodes of created objects.
 * When the program is rerun the firstly calculated hashcodes are left as is and used to
 * substitute new hashcodes via [sun.misc.Unsafe] in the object headers.
 *
 * To guarantee correct work, ensure that replays are deterministic.
 */
object ObjectInitialHashCodes {
    /**
     * Offset (in bytes) of identity hashcode in an object header.
     *
     * @see <a href="https://shipilev.net/jvm/anatomy-quarks/26-identity-hash-code/#_hashcode_storage">JVM Anatomy Quark #26: Identity Hash Code</a>
     */
    private const val IDENTITY_HASHCODE_OFFSET: Long = 1L
    private val initialHashCodes = ConcurrentHashMap<Int, Int>()
    private val nextObjectId = AtomicInteger(0)

    /**
     * This method substitutes identity hash code of the object in its header with the initial (from the first test execution) identity hashcode.
     * @return id of the created object.
     */
    @OptIn(ExperimentalStdlibApi::class)
    fun onNewTrackedObjectCreation(obj: Any): Int {
        val currentObjectId = getNextObjectId()
        val initialIdentityHashCode = getInitialIdentityHashCode(
            currentObjectId,
            System.identityHashCode(obj)
        )
        //println("afterNewObjectCreation: system-hashcode=${System.identityHashCode(obj).toHexString()}, initial=${initialIdentityHashCode.toHexString()} (${obj})")
        // TODO: bizarre and crazy code below (might not work for all JVM implementations)
        UnsafeHolder.UNSAFE.putInt(obj, IDENTITY_HASHCODE_OFFSET, initialIdentityHashCode)
        //println("Unsafe call completed: obj=${obj}")

        return currentObjectId
    }

    /**
     * Resets ids numeration and starts them from 0.
     */
    fun resetObjectIds() = nextObjectId.set(0)

    /**
     * @return id of current object and increments the global counter.
     */
    private fun getNextObjectId(): Int = nextObjectId.getAndIncrement()

    /**
     * @return initial identity hashcode for object with specified [objectId].
     * If this is first time function is called for this object, then provided [identityHashCode] is treated as initial.
     */
    private fun getInitialIdentityHashCode(objectId: Int, identityHashCode: Int): Int = initialHashCodes.getOrPut(objectId) { identityHashCode }
}