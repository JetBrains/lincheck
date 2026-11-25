/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.transformation

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.Test
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/**
 * This test checks that despite some object isn't explicitly assigned to some shared value,
 * is won't be treated like a local object if we wrote it into some shared object using [VarHandle].
 *
 * If we hadn't such check, this test would hang due to infinite spin-loop on a local object operations with
 * no chances to detect a cycle and switch.
 */
@Suppress("Since15")
class VarHandleLocalObjectsTest {

    companion object {
        val nodeHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleLocalObjectsTest::class.java)
            .findVarHandle(VarHandleLocalObjectsTest::class.java, "node", Node::class.java)
    }

    @Volatile
    var node: Node = Node(0)

    @Volatile
    var flag = false

    @Operation
    fun checkWithSpinCycle() {
        val curr = node
        while (curr.value == 1) {
            // spin-wait
        }
    }

    @Operation
    fun setWithVarHandle()  = updateNode { nextNode ->
        nodeHandle.set(this, nextNode)
    }

    @Operation
    fun setVolatileWithVarHandle() = updateNode { nextNode ->
        nodeHandle.setVolatile(this, nextNode)
    }

    @Operation
    fun setReleaseWithVarHandle() = updateNode { nextNode ->
        nodeHandle.setRelease(this, nextNode)
    }

    @Operation
    fun setOpaqueWithVarHandle() = updateNode { nextNode ->
        nodeHandle.setOpaque(this, nextNode)
    }

    @Operation
    fun getAndSetWithVarHandle()= updateNode { nextNode ->
        nodeHandle.getAndSet(this, nextNode)
    }

    @Operation
    fun compareAndSetWithVarHandle() = updateNode { nextNode ->
        nodeHandle.compareAndSet(this, node, nextNode)
    }

    @Operation
    fun compareAndExchangeAcquireWithVarHandle() = updateNode { nextNode ->
        nodeHandle.compareAndExchangeAcquire(this, node, nextNode)
    }

    @Operation
    fun compareAndExchangeReleaseWithVarHandle() = updateNode { nextNode ->
        nodeHandle.compareAndExchangeRelease(this, node, nextNode)
    }

    @Operation
    fun compareAndExchangeWithVarHandle() = updateNode { nextNode ->
        nodeHandle.compareAndExchange(this, node, nextNode)
    }

    @Operation
    fun weakCompareAndSetWithVarHandle() = updateNode { nextNode ->
        nodeHandle.weakCompareAndSet(this, node, nextNode)
    }

    @Operation
    fun weakCompareAndSetAcquireWithVarHandle() = updateNode { nextNode ->
        nodeHandle.weakCompareAndSetAcquire(this, node, nextNode)
    }

    @Operation
    fun weakCompareAndSetPlainWithVarHandle() = updateNode { nextNode ->
        nodeHandle.weakCompareAndSetPlain(this, node, nextNode)
    }

    @Operation
    fun weakCompareAndSetReleaseWithVarHandle() = updateNode { nextNode ->
        nodeHandle.weakCompareAndSetRelease(this, node, nextNode)
    }

    @Test
    fun testSetWithVarHandle() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::setWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    @Test
    fun testSetVolatileWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::setVolatileWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    @Test
    fun testSetReleaseWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::setReleaseWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    @Test
    fun testSetOpaqueWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::setOpaqueWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    @Test
    fun testGetAndSetWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::getAndSetWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)


    @Test
    fun testCompareAndSetWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::compareAndSetWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    @Test
    fun testCompareAndExchangeWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::compareAndExchangeWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    @Test
    fun testWeakCompareAndSetWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::weakCompareAndSetWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)


    @Test
    fun testWeakCompareAndSetAcquireWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::weakCompareAndSetAcquireWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    @Test
    fun testWeakCompareAndSetReleaseWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::weakCompareAndSetReleaseWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    @Test
    fun testWeakCompareAndSetPlainWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::weakCompareAndSetPlainWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    @Test
    fun testCompareAndExchangeAcquireWithVarHandleWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::compareAndExchangeAcquireWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    @Test
    fun testCompareAndExchangeReleaseWithVarHandleWithVarHandleWithUnsafe() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 0 // we will run only custom scenarios
            addCustomScenario {
                parallel {
                    thread { actor(::compareAndExchangeReleaseWithVarHandle) }
                    thread { actor(::checkWithSpinCycle) }
                }
            }
        }
    }, this::class)

    private inline fun updateNode(updateAction: (Node) -> Unit) {
        val nextNode = Node(1)
        nextNode.value = 1
        updateAction(nextNode)
        // If we have a bug and treat nextNode as a local object, we have to insert here some switch points
        // to catch a moment when `node.value` is just updated to 1
        flag = true

        nextNode.value = 0
    }
}