package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions
import kotlin.test.Test

class Structure {
    @Operation
    fun noArgsOp() {}

    @Operation
    fun argsOp(value: Int) {}

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
        .check(this::class)
}
