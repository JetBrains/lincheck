/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

// we need to use some "legal" package for the bootstrap class loader
@file:Suppress("PackageDirectoryMismatch")

package sun.nio.ch.lincheck

import java.util.*

internal interface SharedEventsTracker {
    fun lock(monitor: Any, codeLocation: Int)
    fun unlock(monitor: Any, codeLocation: Int)

    fun park(codeLocation: Int)
    fun unpark(thread: Thread, codeLocation: Int)

    fun wait(monitor: Any, codeLocation: Int, withTimeout: Boolean)
    fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean)

    fun beforeReadField(obj: Any, className: String, fieldName: String, codeLocation: Int)
    fun beforeReadFieldStatic(className: String, fieldName: String, codeLocation: Int)
    fun beforeReadArrayElement(array: Any, index: Int, codeLocation: Int)
    fun afterRead(value: Any?)

    fun beforeWriteField(obj: Any, className: String, fieldName: String, value: Any?, codeLocation: Int)
    fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int)
    fun beforeWriteArrayElement(array: Any, index: Int, value: Any?, codeLocation: Int)

    fun beforeMethodCall0(owner: Any?, className: String, methodName: String, codeLocation: Int)
    fun beforeMethodCall1(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?)
    fun beforeMethodCall2(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?)
    fun beforeMethodCall3(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?)
    fun beforeMethodCall4(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?, param4: Any?)
    fun beforeMethodCall5(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?, param4: Any?, param5: Any?)
    fun beforeMethodCall(owner: Any?, className: String, methodName: String, codeLocation: Int, params: Array<Any?>)

    /* Atomic methods */
    fun beforeAtomicMethodCall0(ownerName: String, methodName: String, codeLocation: Int)
    fun beforeAtomicMethodCall1(ownerName: String, methodName: String, codeLocation: Int, param1: Any?)
    fun beforeAtomicMethodCall2(ownerName: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?)
    fun beforeAtomicMethodCall3(ownerName: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?)
    fun beforeAtomicMethodCall4(ownerName: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?, param4: Any?)
    fun beforeAtomicMethodCall5(ownerName: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?, param4: Any?, param5: Any?)

    fun beforeAtomicMethodCall(ownerName: String, methodName: String, codeLocation: Int, params: Array<Any?>)

    /* Atomic updater methods */
    fun beforeAtomicUpdaterMethodCall0(owner: Any, methodName: String, codeLocation: Int)
    fun beforeAtomicUpdaterMethodCall1(owner: Any, methodName: String, codeLocation: Int, param1: Any?)
    fun beforeAtomicUpdaterMethodCall2(owner: Any, methodName: String, codeLocation: Int, param1: Any?, param2: Any?)
    fun beforeAtomicUpdaterMethodCall3(owner: Any, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?)
    fun beforeAtomicUpdaterMethodCall4(owner: Any, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?, param4: Any?)
    fun beforeAtomicUpdaterMethodCall5(owner: Any, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?, param4: Any?, param5: Any?)

    fun beforeAtomicUpdaterMethodCall(owner: Any, methodName: String, codeLocation: Int, params: Array<Any?>)

    fun onMethodCallFinishedSuccessfully(result: Any?)
    fun onMethodCallThrewException(t: Throwable)

    fun getThreadLocalRandom(): Random

    fun onNewObjectCreation(obj: Any)

    /**
     * @return 0 -> None, 1 -> IGNORE, 2 -> TREAT_AS_ATOMIC
     */
    fun methodGuaranteeType(owner: Any?, className: String, methodName: String): Int
}
