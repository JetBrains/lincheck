package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test

class Structure {
    @Operation
    fun noArgsOp() {}

    @Operation
    fun argsOp(value: Int) {}

    @Test
    fun test() = Lincheck.runConcurrentTest(LincheckSettings {
        stress()
        scenarioGeneration {
            addCustomScenario {
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
        }
    }, this::class)
}
