/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.execution.ActorGenerator
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator.Dummy
import java.lang.Exception
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.util.*
import java.util.stream.Collectors
import kotlin.collections.HashMap

actual typealias ValidationFunction = Method

actual val ValidationFunction.name get() = this.name

actual typealias StateRepresentationFunction = Method

/**
 * Contains information about the provided operations (see [Operation]).
 * Several [tests][StressCTest] can refer to one structure
 * (i.e. one test class could have several [StressCTest] annotations)
 */
actual class CTestStructure private constructor(
    actual val actorGenerators: List<ActorGenerator>,
    actual val operationGroups: List<OperationGroup>,
    val validationFunctions: List<ValidationFunction>,
    val stateRepresentation: StateRepresentationFunction?
) {
    companion object {
        /**
         * Constructs [CTestStructure] for the specified test class.
         */
        fun getFromTestClass(testClass: Class<*>?): CTestStructure {
            val namedGens: MutableMap<String, ParameterGenerator<*>> = HashMap()
            val groupConfigs: MutableMap<String, OperationGroup> = HashMap()
            val actorGenerators: MutableList<ActorGenerator> = ArrayList()
            val validationFunctions: MutableList<ValidationFunction> = ArrayList()
            val stateRepresentations: MutableList<StateRepresentationFunction> = ArrayList()
            var clazz = testClass
            while (clazz != null) {
                readTestStructureFromClass(clazz, namedGens, groupConfigs, actorGenerators, validationFunctions, stateRepresentations)
                clazz = clazz.superclass
            }
            check(stateRepresentations.size <= 1) {
                "Several functions marked with " + StateRepresentation::class.java.simpleName +
                        " were found, while at most one should be specified: " +
                        stateRepresentations.stream().map { obj: StateRepresentationFunction -> obj.name }.collect(Collectors.joining(", "))
            }
            var stateRepresentation: StateRepresentationFunction? = null
            if (stateRepresentations.isNotEmpty()) stateRepresentation = stateRepresentations[0]
            // Create StressCTest class configuration
            return CTestStructure(actorGenerators, ArrayList(groupConfigs.values), validationFunctions, stateRepresentation)
        }

        private fun readTestStructureFromClass(clazz: Class<*>, namedGens: MutableMap<String, ParameterGenerator<*>>,
                                               groupConfigs: MutableMap<String, OperationGroup>,
                                               actorGenerators: MutableList<ActorGenerator>,
                                               validationFunctions: MutableList<ValidationFunction>,
                                               stateRepresentations: MutableList<StateRepresentationFunction>) {
            // Read named parameter paramgen (declared for class)
            for (paramAnn in clazz.getAnnotationsByType(Param::class.java)) {
                require(!paramAnn.name.isEmpty()) { "@Param name in class declaration cannot be empty" }
                namedGens[paramAnn.name] = createGenerator(paramAnn)
            }
            // Create map for default (not named) gens
            val defaultGens = createDefaultGenerators()
            // Read group configurations
            for (opGroupConfigAnn in clazz.getAnnotationsByType(OpGroupConfig::class.java)) {
                groupConfigs[opGroupConfigAnn.name] = OperationGroup(opGroupConfigAnn.name,
                        opGroupConfigAnn.nonParallel)
            }
            // Create actor paramgen
            for (m in getDeclaredMethodSorted(clazz)) {
                // Operation
                if (m.isAnnotationPresent(Operation::class.java)) {
                    val opAnn = m.getAnnotation(Operation::class.java)
                    val isSuspendableMethod = m.isSuspendable()
                    // Check that params() in @Operation is empty or has the same size as the method
                    require(!(opAnn.params.isNotEmpty() && opAnn.params.size != m.parameterCount)) {
                        ("Invalid count of paramgen for " + m.toString()
                                + " method in @Operation")
                    }
                    // Construct list of parameter paramgen
                    val gens: MutableList<ParameterGenerator<*>> = ArrayList()
                    val nParameters = m.parameterCount - if (isSuspendableMethod) 1 else 0
                    for (i in 0 until nParameters) {
                        val nameInOperation = if (opAnn.params.isNotEmpty()) opAnn.params[i] else null
                        gens.add(getOrCreateGenerator(m, m.parameters[i], nameInOperation, namedGens, defaultGens))
                    }
                    // Get list of handled exceptions if they are presented
                    val handledExceptions: List<Class<out Throwable?>> = opAnn.handleExceptionsAsResult.map { it.java }
                    val actorGenerator = ActorGenerator(m, gens, handledExceptions, opAnn.runOnce,
                            opAnn.cancellableOnSuspension, opAnn.allowExtraSuspension, opAnn.blocking, opAnn.causesBlocking,
                            opAnn.promptCancellation)
                    actorGenerators.add(actorGenerator)
                    // Get list of groups and add this operation to specified ones
                    val opGroup = opAnn.group
                    if (!opGroup.isEmpty()) {
                        val operationGroup = groupConfigs[opGroup]
                                ?: throw IllegalStateException("Operation group $opGroup is not configured")
                        operationGroup.actors.add(actorGenerator)
                    }
                }
                if (m.isAnnotationPresent(Validate::class.java)) {
                    check(m.parameterCount == 0) { "Validation function " + m.name + " should not have parameters" }
                    validationFunctions.add(m)
                }
                if (m.isAnnotationPresent(StateRepresentation::class.java)) {
                    check(m.parameterCount == 0) { "State representation function " + m.name + " should not have parameters" }
                    check(m.returnType == String::class.java) { "State representation function " + m.name + " should have String return type" }
                    stateRepresentations.add(m)
                }
            }
        }

        /**
         * Sort methods by name to make scenario generation deterministic.
         */
        private fun getDeclaredMethodSorted(clazz: Class<*>): Array<Method> {
            val methods = clazz.declaredMethods
            val comparator = Comparator // compare by method name
                    .comparing { obj: Method -> obj.name } // then compare by parameter class names
                    .thenComparing { m: Method -> Arrays.stream(m.parameterTypes).map { obj: Class<*> -> obj.name }.collect(Collectors.joining(":")) }
            Arrays.sort(methods, comparator)
            return methods
        }

        private fun getOrCreateGenerator(m: Method, p: Parameter, nameInOperation: String?,
                                         namedGens: Map<String, ParameterGenerator<*>>, defaultGens: Map<Class<*>?, ParameterGenerator<*>>): ParameterGenerator<*> {
            // Read @Param annotation on the parameter
            val paramAnn = p.getAnnotation(Param::class.java)
            // If this annotation not presented use named generator based on name presented in @Operation or parameter name.
            if (paramAnn == null) {
                // If name in @Operation is presented, return the generator with this name,
                // otherwise return generator with parameter's name
                val name = nameInOperation ?: if (p.isNamePresent) p.name else null
                if (name != null) return checkAndGetNamedGenerator(namedGens, name)
                // Parameter generator is not specified, try to create a default one
                val parameterType = p.type
                val defaultGenerator = defaultGens[parameterType]
                if (defaultGenerator != null) return defaultGenerator
                throw IllegalStateException("Generator for parameter \"" + p + "\" in method \""
                        + m.name + "\" should be specified.")
            }
            // If the @Param annotation is presented check it's correctness firstly
            check(!(!paramAnn.name.isEmpty() && paramAnn.gen != Dummy::class)) { "@Param should have either name or gen with optionally configuration" }
            // If @Param annotation specifies generator's name then return the specified generator
            return if (!paramAnn.name.isEmpty()) checkAndGetNamedGenerator(namedGens, paramAnn.name) else createGenerator(paramAnn)
            // Otherwise create new parameter generator
        }

        private fun createGenerator(paramAnn: Param): ParameterGenerator<*> {
            return try {
                paramAnn.gen.java.getConstructor(String::class.java).newInstance(paramAnn.conf)
            } catch (e: Exception) {
                throw IllegalStateException("Cannot create parameter gen", e)
            }
        }

        private fun createDefaultGenerators(): Map<Class<*>?, ParameterGenerator<*>> {
            val defaultGens: MutableMap<Class<*>?, ParameterGenerator<*>> = HashMap()
            defaultGens[Boolean::class.javaPrimitiveType] = BooleanGen("")
            defaultGens[Boolean::class.javaObjectType] = defaultGens[Boolean::class.javaPrimitiveType]!!
            defaultGens[Byte::class.javaPrimitiveType] = ByteGen("")
            defaultGens[Byte::class.javaObjectType] = defaultGens[Byte::class.javaPrimitiveType]!!
            defaultGens[Short::class.javaPrimitiveType] = ShortGen("")
            defaultGens[Short::class.javaObjectType] = defaultGens[Short::class.javaPrimitiveType]!!
            defaultGens[Int::class.javaPrimitiveType] = IntGen("")
            defaultGens[Int::class.javaObjectType] = defaultGens[Int::class.javaPrimitiveType]!!
            defaultGens[Long::class.javaPrimitiveType] = LongGen("")
            defaultGens[Long::class.javaObjectType] = defaultGens[Long::class.javaPrimitiveType]!!
            defaultGens[Float::class.javaPrimitiveType] = FloatGen("")
            defaultGens[Float::class.javaObjectType] = defaultGens[Float::class.javaPrimitiveType]!!
            defaultGens[Double::class.javaPrimitiveType] = DoubleGen("")
            defaultGens[Double::class.javaObjectType] = defaultGens[Double::class.javaPrimitiveType]!!
            defaultGens[String::class.javaObjectType] = StringGen("")
            return defaultGens
        }

        private fun checkAndGetNamedGenerator(namedGens: Map<String, ParameterGenerator<*>>, name: String): ParameterGenerator<*> {
            return Objects.requireNonNull(namedGens[name], "Unknown generator name: \"$name\"")!!
        }
    }
}