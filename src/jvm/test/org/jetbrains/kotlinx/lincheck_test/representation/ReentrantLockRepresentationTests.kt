/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

class ReentrantLockSingleLockRepresentationTest: BaseRunConcurrentRepresentationTest<Unit>("reentrant_lock_single_lock") {
    private var a = 0
    private val lock = ReentrantLock()
    
    override fun block() {
        lock.lock()
        a++
        lock.unlock()
        check(false)
    }

    override val analyzeStdLib: Boolean = false
}

class SemaphoreSwitchRepresentationTest: BaseRunConcurrentRepresentationTest<Unit>("semaphore_switch") {

    override fun block() {
        val semA = Semaphore(0)
        val semB = Semaphore(0)
        
        val t1 = thread {
            semB.release()
            semA.acquire()
        }
        
        val t2 = thread {
            semB.acquire()
            semA.release()
        }
        
        t1.join()
        t2.join()
        check(false)
    }
    
    override val analyzeStdLib: Boolean = false
}
