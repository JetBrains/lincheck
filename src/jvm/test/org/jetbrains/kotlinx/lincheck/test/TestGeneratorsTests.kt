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

package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.JavaExecutionScenarioTestGenerator
import org.jetbrains.kotlinx.lincheck.KotlinExecutionScenarioTestGenerator
import org.jetbrains.kotlinx.lincheck.SupportedTestLanguage
import org.jetbrains.kotlinx.lincheck.determineTestLanguage
import org.jetbrains.kotlinx.lincheck.dsl.ScenarioBuilder
import org.jetbrains.kotlinx.lincheck.scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.StringBuilder
import kotlin.coroutines.Continuation

class JavaExecutionScenarioTestGeneratorTest : AbstractDSLTest() {
    @Test
    fun `should generate java dsl scenario test`() {
        val expectedScenarioTest = """
            .addCustomScenario(
            		new ScenarioBuilder(this.getClass())
            				.initial(
            					actor("intOperation", 1),
            					actor("byteOperation", (byte) 42)
            				)
            				.parallel(
            					thread(
            						actor("shortOperation", (short) 3),
            						actor("stringOperation", "string")
            					),
            					thread(
            						actor("booleanOperation", true),
            						actor("longOperation", 112L),
            						actor("doubleOperation", 1.2)
            					)
            				)
            				.post(
            					actor("floatOperation", 1.2F),
            					actor("charOperation", (char) 5)
            				)
            		.build()
            )
        """.trimIndent()

        val scenario = ScenarioBuilder(this::class.java)
            .initial(
                ScenarioBuilder.actor("intOperation", 1),
                ScenarioBuilder.actor("byteOperation", 42.toByte())
            )
            .parallel(
                ScenarioBuilder.thread(
                    ScenarioBuilder.actor("shortOperation", 3.toShort()),
                    ScenarioBuilder.actor("stringOperation", "string")
                ),
                ScenarioBuilder.thread(
                    ScenarioBuilder.actor("booleanOperation", true),
                    ScenarioBuilder.actor("longOperation", 112L),
                    ScenarioBuilder.actor("doubleOperation", 1.2)
                )
            )
            .post(
                ScenarioBuilder.actor("floatOperation", 1.2F),
                ScenarioBuilder.actor("charOperation", 5.toChar())
            )
            .build()

        val scenarioTest = JavaExecutionScenarioTestGenerator.generateTestCode(scenario).trimIndent()

        assertEquals(expectedScenarioTest, scenarioTest)
    }

    @Test
    fun `should generate test with operation without arguments test`() {
        val expectedScenarioTest = """
            .addCustomScenario(
            		new ScenarioBuilder(this.getClass())
            				.initial(
            					actor("noArgsOperation")
            				)
            		.build()
            )
        """.trimIndent()

        val scenario = ScenarioBuilder(this::class.java)
            .initial(
                ScenarioBuilder.actor("noArgsOperation"),
            )
            .build()

        val scenarioTest = JavaExecutionScenarioTestGenerator.generateTestCode(scenario).trimIndent()
        assertEquals(expectedScenarioTest, scenarioTest)
    }

    @Test
    fun `should generate test with operation many arguments test`() {
        val expectedScenarioTest = """
            .addCustomScenario(
            		new ScenarioBuilder(this.getClass())
            				.initial(
            					actor("twoArgsOperations", 1, 2)
            				)
            		.build()
            )
        """.trimIndent()

        val scenario = ScenarioBuilder(this::class.java)
            .initial(
                ScenarioBuilder.actor("twoArgsOperations", 1, 2)
            )
            .build()

        val scenarioTest = JavaExecutionScenarioTestGenerator.generateTestCode(scenario).trimIndent()
        assertEquals(expectedScenarioTest, scenarioTest)
    }

}


class KotlinExecutionScenarioTestGenerator : AbstractDSLTest() {
    @Test
    fun `should generate kotlin dsl scenario test`() {
        val expectedScenarioTest = """
            .addCustomScenario {
            	initial {
            		actor(::intOperation, 1)
            		actor(::byteOperation, 42.toByte())
            	}
            	parallel {
            		thread {
            			actor(::shortOperation, 3.toShort())
            			actor(::stringOperation, "string")
            		}
            		thread {
            			actor(::booleanOperation, true)
            			actor(::longOperation, 112L)
            			actor(::doubleOperation, 1.2)
            		}
            	}
            	post {
            		actor(::floatOperation, 1.2F)
            		actor(::charOperation, 5.toChar())
            	}
            }
        """.trimIndent()

        val scenario = scenario {
            initial {
                actor(::intOperation, 1)
                actor(::byteOperation, 42.toByte())
            }
            parallel {
                thread {
                    actor(::shortOperation, 3.toShort())
                    actor(::stringOperation, "string")
                }
                thread {
                    actor(::booleanOperation, true)
                    actor(::longOperation, 112L)
                    actor(::doubleOperation, 1.2)
                }
            }
            post {
                actor(::floatOperation, 1.2F)
                actor(::charOperation, 5.toChar())
            }
        }

        val scenarioTest = KotlinExecutionScenarioTestGenerator.generateTestCode(scenario).trimIndent()
        assertEquals(expectedScenarioTest, scenarioTest)
    }

    @Test
    fun `should generate test with operation without arguments test`() {
        val expectedScenarioTest = """
            .addCustomScenario {
            	initial {
            		actor(::noArgsOperation)
            	}
            }
        """.trimIndent()

        val scenario = scenario {
            initial {
                actor(::noArgsOperation)
            }
        }

        val scenarioTest = KotlinExecutionScenarioTestGenerator.generateTestCode(scenario).trimIndent()
        assertEquals(expectedScenarioTest, scenarioTest)
    }

    @Test
    fun `should generate test with operation many arguments test`() {
        val expectedScenarioTest = """
            .addCustomScenario {
            	initial {
            		actor(::twoArgsOperations, 1, 2)
            	}
            }
        """.trimIndent()

        val scenario = scenario {
            initial {
                actor(::twoArgsOperations, 1, 2)
            }
        }

        val scenarioTest = KotlinExecutionScenarioTestGenerator.generateTestCode(scenario).trimIndent()
        assertEquals(expectedScenarioTest, scenarioTest)
    }


}

@Suppress("UNUSED")
abstract class AbstractDSLTest {
    fun intOperation(value: Int) = Unit

    fun byteOperation(value: Byte) = Unit

    fun shortOperation(value: Short) = Unit

    fun stringOperation(value: String) = Unit

    fun booleanOperation(value: Boolean) = Unit

    fun longOperation(value: Long) = Unit

    fun doubleOperation(value: Double) = Unit

    fun floatOperation(value: Float) = Unit

    fun charOperation(value: Char) = Unit

    fun noArgsOperation() = Unit

    fun twoArgsOperations(first: Int, second: Int) = Unit
}

class TestGeneratorsTests {

    @Test
    fun `should determine java test language`() {
        val stackTrace = arrayOf(
            STUB_STACKTRACE_ELEMENT,
            LINCHECK_METHOD_STACKTRACE_ELEMENT,
            LINCHECK_METHOD_STACKTRACE_ELEMENT,
            JAVA_CALLER_STACKTRACE_ELEMENT,
            STUB_STACKTRACE_ELEMENT
        )
        val language = determineTestLanguage(stackTrace)

        assertEquals(SupportedTestLanguage.JAVA, language)
    }

    @Test
    fun `should determine kotlin test language`() {
        val stackTrace = arrayOf(
            STUB_STACKTRACE_ELEMENT,
            LINCHECK_METHOD_STACKTRACE_ELEMENT,
            LINCHECK_METHOD_STACKTRACE_ELEMENT,
            KOTLIN_CALLER_STACKTRACE_ELEMENT,
            STUB_STACKTRACE_ELEMENT
        )
        val language = determineTestLanguage(stackTrace)

        assertEquals(SupportedTestLanguage.KOTLIN, language)
    }

    @Test
    fun `shouldn't determine test language`() {
        val stackTrace = arrayOf(
            STUB_STACKTRACE_ELEMENT,
            STUB_STACKTRACE_ELEMENT,
            STUB_STACKTRACE_ELEMENT,
            STUB_STACKTRACE_ELEMENT,
            STUB_STACKTRACE_ELEMENT
        )
        val language = determineTestLanguage(stackTrace)

        assertNull(language)
    }

    companion object {
        private val STUB_STACKTRACE_ELEMENT = StackTraceElement("stub", "stub", "stub", 1)

        private val JAVA_CALLER_STACKTRACE_ELEMENT =
            StackTraceElement(StringBuilder::class.java.canonicalName, "check", "stub", 1)

        private val KOTLIN_CALLER_STACKTRACE_ELEMENT =
            StackTraceElement(Continuation::class.qualifiedName, "check", "stub", 1)

        private val LINCHECK_METHOD_STACKTRACE_ELEMENT =
            StackTraceElement(LinChecker::class.java.canonicalName, "check2", "stub", 1)
    }

}
