package org.jetbrains.kotlinx.lincheck;

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import org.jetbrains.kotlinx.lincheck.annotations.*;
import org.jetbrains.kotlinx.lincheck.execution.ActorGenerator;
import org.jetbrains.kotlinx.lincheck.paramgen.*;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static org.jetbrains.kotlinx.lincheck.ActorKt.isSuspendable;

/**
 * Contains information about the provided operations (see {@link Operation}).
 * Several {@link StressCTest tests} can refer to one structure
 * (i.e. one test class could have several {@link StressCTest} annotations)
 */
public class CTestStructure {
    public final List<ActorGenerator> actorGenerators;
    public final List<OperationGroup> operationGroups;
    public final List<Method> validationFunctions;

    private CTestStructure(List<ActorGenerator> actorGenerators, List<OperationGroup> operationGroups, List<Method> validationFunctions) {
        this.actorGenerators = actorGenerators;
        this.operationGroups = operationGroups;
        this.validationFunctions = validationFunctions;
    }

    /**
     * Constructs {@link CTestStructure} for the specified test class.
     */
    public static CTestStructure getFromTestClass(Class<?> testClass) {
        Map<String, ParameterGenerator<?>> namedGens = new HashMap<>();
        Map<String, OperationGroup> groupConfigs = new HashMap<>();
        List<ActorGenerator> actorGenerators = new ArrayList<>();
        List<Method> validationFunctions = new ArrayList<>();
        Class<?> clazz = testClass;
        while (clazz != null) {
            readTestStructureFromClass(clazz, namedGens, groupConfigs, actorGenerators, validationFunctions);
            clazz = clazz.getSuperclass();
        }
        // Create StressCTest class configuration
        return new CTestStructure(actorGenerators, new ArrayList<>(groupConfigs.values()), validationFunctions);
    }

    private static void readTestStructureFromClass(Class<?> clazz, Map<String, ParameterGenerator<?>> namedGens,
                                                   Map<String, OperationGroup> groupConfigs,
                                                   List<ActorGenerator> actorGenerators,
                                                   List<Method> validationFunctions
    ) {
        // Read named parameter paramgen (declared for class)
        for (Param paramAnn : clazz.getAnnotationsByType(Param.class)) {
            if (paramAnn.name().isEmpty()) {
                throw new IllegalArgumentException("@Param name in class declaration cannot be empty");
            }
            namedGens.put(paramAnn.name(), createGenerator(paramAnn));
        }
        // Read group configurations
        for (OpGroupConfig opGroupConfigAnn: clazz.getAnnotationsByType(OpGroupConfig.class)) {
            groupConfigs.put(opGroupConfigAnn.name(), new OperationGroup(opGroupConfigAnn.name(),
                    opGroupConfigAnn.nonParallel()));
        }
        // Create actor paramgen
        for (Method m : clazz.getDeclaredMethods()) {
            // Operation
            if (m.isAnnotationPresent(Operation.class)) {
                Operation operationAnn = m.getAnnotation(Operation.class);
                boolean isSuspendableMethod = isSuspendable(m);
                // Check that params() in @Operation is empty or has the same size as the method
                if (operationAnn.params().length > 0 && operationAnn.params().length != m.getParameterCount()) {
                    throw new IllegalArgumentException("Invalid count of paramgen for " + m.toString()
                            + " method in @Operation");
                }
                // Construct list of parameter paramgen
                final List<ParameterGenerator<?>> gens = new ArrayList<>();
                int nParameters = m.getParameterCount() - (isSuspendableMethod ? 1 : 0);
                for (int i = 0; i < nParameters; i++) {
                    String nameInOperation = operationAnn.params().length > 0 ? operationAnn.params()[i] : null;
                    gens.add(getOrCreateGenerator(m, m.getParameters()[i], nameInOperation, namedGens));
                }
                // Get list of handled exceptions if they are presented
                List<Class<? extends Throwable>> handledExceptions = Arrays.asList(operationAnn.handleExceptionsAsResult());
                ActorGenerator actorGenerator = new ActorGenerator(m, gens, handledExceptions, operationAnn.runOnce(), operationAnn.cancellableOnSuspension());
                actorGenerators.add(actorGenerator);
                // Get list of groups and add this operation to specified ones
                String opGroup = operationAnn.group();
                if (!opGroup.isEmpty()) {
                    OperationGroup operationGroup = groupConfigs.get(opGroup);
                    if (operationGroup == null)
                        throw new IllegalStateException("Operation group " + opGroup + " is not configured");
                    operationGroup.actors.add(actorGenerator);
                }
            }
            if (m.isAnnotationPresent(Validate.class)) {
                if (m.getParameterCount() != 0)
                    throw new IllegalStateException("Validation function " + m.getName() + " should not have parameters");
                validationFunctions.add(m);
            }
        }
    }

    private static ParameterGenerator<?> getOrCreateGenerator(Method m, Parameter p, String nameInOperation,
        Map<String, ParameterGenerator<?>> namedGens)
    {
        // Read @Param annotation on the parameter
        Param paramAnn = p.getAnnotation(Param.class);
        // If this annotation not presented use named generator based on name presented in @Operation or parameter name.
        if (paramAnn == null) {
            // If name in @Operation is presented, return the generator with this name,
            // otherwise return generator with parameter's name
            String name = nameInOperation != null ? nameInOperation :
                (p.isNamePresent() ? p.getName() : null);
            if (name != null)
                return checkAndGetNamedGenerator(namedGens, name);
            // Parameter generator is not specified, try to create a default one
            ParameterGenerator<?> defaultGenerator = createDefaultGenerator(p);
            if (defaultGenerator != null)
                return defaultGenerator;
            // Cannot create default parameter generator, throw an exception
            throw new IllegalStateException("Generator for parameter \'" + p + "\" in method \""
                + m.getName() + "\" should be specified.");
        }
        // If the @Param annotation is presented check it's correctness firstly
        if (!paramAnn.name().isEmpty() && !(paramAnn.gen() == ParameterGenerator.Dummy.class))
            throw new IllegalStateException("@Param should have either name or gen with optionally configuration");
        // If @Param annotation specifies generator's name then return the specified generator
        if (!paramAnn.name().isEmpty())
            return checkAndGetNamedGenerator(namedGens, paramAnn.name());
        // Otherwise create new parameter generator
        return createGenerator(paramAnn);
    }

    private static ParameterGenerator<?> createGenerator(Param paramAnn) {
        try {
            return paramAnn.gen().getConstructor(String.class).newInstance(paramAnn.conf());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create parameter gen", e);
        }
    }

    private static ParameterGenerator<?> createDefaultGenerator(Parameter p) {
        Class<?> t = p.getType();
        if (t == byte.class   || t == Byte.class)    return new ByteGen("");
        if (t == short.class  || t == Short.class)   return new ShortGen("");
        if (t == int.class    || t == Integer.class) return new IntGen("");
        if (t == long.class   || t == Long.class)    return new LongGen("");
        if (t == float.class  || t == Float.class)   return new FloatGen("");
        if (t == double.class || t == Double.class)  return new DoubleGen("");
        if (t == String.class) return new StringGen("");
        return null;
    }

    private static ParameterGenerator<?> checkAndGetNamedGenerator(Map<String, ParameterGenerator<?>> namedGens, String name) {
        return Objects.requireNonNull(namedGens.get(name), "Unknown generator name: \"" + name + "\"");
    }

    public static class OperationGroup {
        public final String name;
        public final boolean nonParallel;
        public final List<ActorGenerator> actors;

        public OperationGroup(String name, boolean nonParallel) {
            this.name = name;
            this.nonParallel = nonParallel;
            this.actors = new ArrayList<>();
        }

        @Override
        public String toString() {
            return "OperationGroup{" +
                "name='" + name + '\'' +
                ", nonParallel=" + nonParallel +
                ", actors=" + actors +
                '}';
        }
    }
}
