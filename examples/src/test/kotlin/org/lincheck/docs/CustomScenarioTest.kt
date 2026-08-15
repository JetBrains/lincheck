package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.util.LoggingLevel
import kotlin.test.Test

class Structure {
    fun noArgsOp() {}
    fun argsOp(value: Int) {}
}

class CustomScenarioTest {
    var struct = Structure()

    @Operation
    fun noArgsOp() = struct.noArgsOp()

    @Operation
    fun argsOp(value: Int) = struct.argsOp(value)

    @Test
    fun test() = StressOptions()
        .addCustomScenario {
            initial {
                actor(Structure::noArgsOp)
            }
            parallel {
                thread {
                    actor(Structure::argsOp, 1)
                    actor(Structure::argsOp, 2)
                }
                thread {
                    actor(Structure::argsOp, 3)
                }
            }
            post {
                actor(Structure::noArgsOp)
            }
        }
        // Report the scenarios even if the test has not failed
        // The custom scenario should be first in the list of scenarios
        .logLevel(LoggingLevel.INFO)
        .check(this::class)
}