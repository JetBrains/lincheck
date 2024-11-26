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

/**
 * Tracks object allocations and changes in object graph topology.
 */
interface ObjectTracker {

    /**
     * Registers a thread with the given id in the object tracker.
     *
     * @param threadId the id of the thread to register.
     * @param thread the thread object to register.
     */
    fun registerThread(threadId: Int, thread: Thread)

    /**
     * Registers a newly created object in the object tracker.
     *
     * @param obj the object to be registered
     */
    fun registerNewObject(obj: Any)

    /**
     * This method is used to register a link between two objects in the object tracker.
     * The link is established from the object specified by the [fromObject] parameter
     * to the object specified by the [toObject] parameter.
     *
     * @param fromObject the object from which the link originates.
     * @param toObject the object to which the link points.
     */
    fun registerObjectLink(fromObject: Any, toObject: Any?)

    /**
     * Determines whether accesses to the fields of the given object should be tracked.
     *
     * @param obj the object to check for tracking.
     * @return true if the object's accesses should be tracked, false otherwise.
     */
    fun shouldTrackObjectAccess(obj: Any): Boolean

    /**
     * Resets the state of the object tracker.
     */
    fun reset()
}

/**
 * Special auxiliary object used as an owner of static fields (instead of `null`).
 */
internal object StaticObject: Any()