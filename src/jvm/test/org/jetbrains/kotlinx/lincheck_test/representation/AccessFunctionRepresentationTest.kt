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

class AccessFunctionRepresentationTest: BaseRunConcurrentRepresentationTest<Unit>("access_function_representation_test") {
    
    @Volatile
    private var a = 0
    
    override fun block() {
        runLambda { inc1() } 
        check(false)
    }
    
    private fun inc1() {
        Nested().inc2()
    }
    
    private fun inc3() {
        a++
    }
    
    private inner class Nested {
         fun inc2() {
            inc3()
        }
        
    }
}

private fun runLambda(r: () -> Unit) {
    r()
}

class AccessFieldRepresentationTest: BaseRunConcurrentRepresentationTest<Unit>("access_field_representation_test") {

    @Volatile
    private var a = 0
    
    override fun block() {
        runLambda { a++ }
        check(false)
    }
}
