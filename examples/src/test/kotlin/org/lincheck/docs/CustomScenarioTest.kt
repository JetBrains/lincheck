package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.util.LoggingLevel
import kotlin.test.Test

class Structure {
    fun foo() {}
    fun buzz(value: Int) {}
}

class CustomScenarioTest {
    var struct = Structure()

    @Operation
    fun foo() = struct.foo()

    @Operation
    fun buzz(value: Int) = struct.buzz(value)

    @Test
    fun test() = StressOptions()
        .addCustomScenario {
            initial {
                actor(Structure::foo)
            }
            parallel {
                thread {
                    actor(Structure::buzz, 1)
                    actor(Structure::buzz, 2)
                }
                thread {
                    actor(Structure::buzz, 3)
                }
            }
            post {
                actor(Structure::foo)
            }
        }
        // Report the scenarios even if the test has not failed
        // The custom scenario should be first in the list of scenarios
        .logLevel(LoggingLevel.INFO)
        .check(this::class)
}