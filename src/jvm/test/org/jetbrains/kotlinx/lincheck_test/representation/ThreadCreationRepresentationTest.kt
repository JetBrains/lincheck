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

import org.jetbrains.kotlinx.lincheck_test.util.TestJdkVersion
import org.jetbrains.kotlinx.lincheck_test.util.testJdkVersion
import kotlin.concurrent.thread

class ThreadCreationRepresentationTest: BaseRunConcurrentRepresentationTest<Unit>(
    when(testJdkVersion) {
        TestJdkVersion.JDK_8 -> "thread_creation_representation_test_jdk_8.txt"
        else -> "thread_creation_representation_test.txt"
    }
) {

    @Volatile
    private var a = 0

    override fun block() {
        val t1 = thread(false, name = "thread1") { callMe() }
        t1.start()
        
        val t2 = thread(true, name = "thread2") { callMe() }
        
        val t3 = thread(name = "thread3", priority = 8) { callMe() }
        
        t1.join()
        t2.join()
        t3.join()
        check(false)
    }

    private fun callMe() {
        a += 1
    }
}