package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import kotlin.test.Test

class Storage {
    var size = 0

    fun add() = ++size
}

class ValidationTest {
    private val storage = Storage()

    @Operation
    fun add() = storage.add()

    @Validate
    fun validate() {
        // Check some property of the data structure
        // Throw an exception if the check is violated
        check(storage.size >= 2) { "Size must be at least 2, but was ${storage.size}" }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread {
                    actor(Storage::add)
                }
                thread {
                    actor(Storage::add)
                }
            }
        }
        .check(this::class)
}
