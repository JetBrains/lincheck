/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck;

import org.jetbrains.kotlinx.lincheck.annotations.*;
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.paramgen.*;
import org.jetbrains.kotlinx.lincheck.strategy.stress.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.*;

import static org.jetbrains.kotlinx.lincheck.ActorKt.*;

/**
 * Contains information about the provided operations (see {@link Operation}).
 * Several {@link StressCTest tests} can refer to one structure
 * (i.e. one test class could have several {@link StressCTest} annotations)
 */
public class CTestStructure {
    public final List<ActorGenerator> actorGenerators;
    public final List<ParameterGenerator<?>> parameterGenerators;
    public final List<OperationGroup> operationGroups;
    public final List<Method> validationFunctions;
    public final Method stateRepresentation;

    public final RandomProvider randomProvider;

    private CTestStructure(List<ActorGenerator> actorGenerators, List<ParameterGenerator<?>> parameterGenerators, List<OperationGroup> operationGroups,
                           List<Method> validationFunctions, Method stateRepresentation, RandomProvider randomProvider) {
        this.actorGenerators = actorGenerators;
        this.parameterGenerators = parameterGenerators;
        this.operationGroups = operationGroups;
        this.validationFunctions = validationFunctions;
        this.stateRepresentation = stateRepresentation;
        this.randomProvider = randomProvider;
    }

    /**
     * Constructs {@link CTestStructure} for the specified test class.
     */
    public static CTestStructure getFromTestClass(Class<?> testClass) {
        Map<String, OperationGroup> groupConfigs = new HashMap<>();
        List<ActorGenerator> actorGenerators = new ArrayList<>();
        List<Method> validationFunctions = new ArrayList<>();
        List<Method> stateRepresentations = new ArrayList<>();
        Class<?> clazz = testClass;
        RandomProvider randomProvider = new RandomProvider();
        Map<Class<?>, ParameterGenerator<?>> parameterGeneratorsMap = new HashMap<>();

        while (clazz != null) {
            readTestStructureFromClass(clazz, groupConfigs, actorGenerators, parameterGeneratorsMap, validationFunctions, stateRepresentations, randomProvider);
            clazz = clazz.getSuperclass();
        }
        if (stateRepresentations.size() > 1) {
            throw new IllegalStateException("Several functions marked with " + StateRepresentation.class.getSimpleName() +
                " were found, while at most one should be specified: " +
                stateRepresentations.stream().map(Method::getName).collect(Collectors.joining(", ")));
        }
        Method stateRepresentation = null;
        if (!stateRepresentations.isEmpty())
            stateRepresentation = stateRepresentations.get(0);
        // Create StressCTest class configuration
        List<ParameterGenerator<?>> parameterGenerators = new ArrayList<>(parameterGeneratorsMap.values());

        return new CTestStructure(actorGenerators, parameterGenerators, new ArrayList<>(groupConfigs.values()), validationFunctions, stateRepresentation, randomProvider);
    }

    private static void readTestStructureFromClass(
            Class<?> clazz,
           Map<String, OperationGroup> groupConfigs,
           List<ActorGenerator> actorGenerators,
           Map<Class<?>, ParameterGenerator<?>> parameterGeneratorsMap,
           List<Method> validationFunctions,
           List<Method> stateRepresentations,
           RandomProvider randomProvider
    ) {
        // Read named parameter generators (declared for class)
        Map<String, ParameterGenerator<?>> namedGens = createNamedGens(clazz, randomProvider);
        // Create map for default (not named) gens
        Map<Class<?>, ParameterGenerator<?>> defaultGens = createDefaultGenerators(randomProvider);
        // Read group configurations
        for (OpGroupConfig opGroupConfigAnn: clazz.getAnnotationsByType(OpGroupConfig.class)) {
            groupConfigs.put(opGroupConfigAnn.name(), new OperationGroup(opGroupConfigAnn.name(),
                    opGroupConfigAnn.nonParallel()));
        }
        // Process class methods
        for (Method m : getDeclaredMethodSorted(clazz)) {
            // Operation
            if (m.isAnnotationPresent(Operation.class)) {
                Operation opAnn = m.getAnnotation(Operation.class);
                boolean isSuspendableMethod = isSuspendable(m);
                // Check that params() in @Operation is empty or has the same size as the method
                if (opAnn.params().length > 0 && opAnn.params().length != m.getParameterCount()) {
                    throw new IllegalArgumentException("Invalid count of parameter generators for " + m);
                }
                // Construct a list of parameter generators
                final List<ParameterGenerator<?>> gens = new ArrayList<>();
                int nParameters = m.getParameterCount() - (isSuspendableMethod ? 1 : 0);
                for (int i = 0; i < nParameters; i++) {
                    String nameInOperation = opAnn.params().length > 0 ? opAnn.params()[i] : null;
                    Parameter parameter = m.getParameters()[i];
                    ParameterGenerator<?> parameterGenerator = getOrCreateGenerator(m, parameter, nameInOperation, namedGens, defaultGens, randomProvider);
                    parameterGeneratorsMap.putIfAbsent(parameter.getType(), parameterGenerator);
                    gens.add(parameterGenerator);
                }
                ActorGenerator actorGenerator = new ActorGenerator(m, gens, opAnn.runOnce(),
                    opAnn.cancellableOnSuspension(), opAnn.allowExtraSuspension(), opAnn.blocking(), opAnn.causesBlocking(),
                    opAnn.promptCancellation());
                actorGenerators.add(actorGenerator);
                // Get list of groups and add this operation to specified ones
                String opGroup = opAnn.group();
                if (!opGroup.isEmpty()) {
                    OperationGroup operationGroup = groupConfigs.get(opGroup);
                    if (operationGroup == null)
                        throw new IllegalStateException("Operation group " + opGroup + " is not configured");
                    operationGroup.actors.add(actorGenerator);
                }
                String opNonParallelGroupName = opAnn.nonParallelGroup();
                if (!opNonParallelGroupName.isEmpty()) { // is `nonParallelGroup` specified?
                    groupConfigs.computeIfAbsent(opNonParallelGroupName, name -> new OperationGroup(name, true));
                    groupConfigs.get(opNonParallelGroupName).actors.add(actorGenerator);
                }
            }
            if (m.isAnnotationPresent(Validate.class)) {
                if (m.getParameterCount() != 0)
                    throw new IllegalStateException("Validation function " + m.getName() + " should not have parameters");
                validationFunctions.add(m);
            }

            if (m.isAnnotationPresent(StateRepresentation.class)) {
                if (m.getParameterCount() != 0)
                    throw new IllegalStateException("State representation function " + m.getName() + " should not have parameters");
                if (m.getReturnType() != String.class)
                    throw new IllegalStateException("State representation function " + m.getName() + " should have String return type");
                stateRepresentations.add(m);
            }
        }
    }

    private static Map<String, ParameterGenerator<?>> createNamedGens(Class<?> clazz, RandomProvider randomProvider) {
        Map<String, ParameterGenerator<?>> namedGens = new HashMap<>();
        // Traverse all operations to determine named EnumGens types
        // or throw if one named enum gen is associated with many types
        Map<String, Class<? extends Enum<?>>> enumGeneratorNameToClassMap = collectNamedEnumGeneratorToClassMap(clazz);
        // Read named parameter generators (declared for class)
        for (Param paramAnn : clazz.getAnnotationsByType(Param.class)) {
            // Throw exception if a user tried to declare EnumGen on the top of the class but without a name
            if (paramAnn.name().isEmpty()) {
                throw new IllegalArgumentException("@Param name in class declaration cannot be empty");
            }
            if (enumGeneratorNameToClassMap.containsKey(paramAnn.name())) {
                Class<? extends Enum<?>> enumClass = enumGeneratorNameToClassMap.get(paramAnn.name());
                namedGens.put(paramAnn.name(), createEnumGenerator(paramAnn.conf(), randomProvider, enumClass));
            } else {
                namedGens.put(paramAnn.name(), createGenerator(paramAnn, randomProvider));
            }
        }
        return namedGens;
    }

    /**
     * This method iterates over all methods in a given class, identifies those that are annotated with `@Operation`,
     * and maps named enum generator names (found in method parameters annotated with `@Param`) to their respective enum classes.
     * It's critical to note that each named enum generator should only be associated with one unique enum class.
     * If the same enum generator name is associated with more than one type, an [IllegalStateException] is thrown.
     *
     * @param clazz The class in which methods and parameters are to be inspected.
     * @return A map pairing each named enum generator (as indicated by `@Param` annotation name field) with its associated enum class.
     * The map keys are enum generator names, and the values are enum classes.
     * @throws IllegalStateException if a named enum generator is found to be associated with more than one enum class,
     *                               which violates the uniqueness principle of enum generator to enum class mapping.
     */
    private static Map<String, Class<? extends Enum<?>>> collectNamedEnumGeneratorToClassMap(Class<?> clazz) {
        Map<String, Class<? extends Enum<?>>> enumGeneratorNameToClassMap = new HashMap<>();

        Arrays.stream(clazz.getDeclaredMethods()) // get all methods of the class
                .filter(method -> method.isAnnotationPresent(Operation.class)) // take methods, annotated as @Operation
                .flatMap(method -> Arrays.stream(method.getParameters())) // get their parameters
                .filter(parameter -> parameter.getType().isEnum() && // which are enums
                                     parameter.isAnnotationPresent(Param.class) && // annotated with @Param
                                     !parameter.getAnnotationsByType(Param.class)[0].name().isEmpty()) // and references to some named EnumGen
                .forEach(parameter -> {
                    String paramGenName = parameter.getAnnotationsByType(Param.class)[0].name();

                    @SuppressWarnings("unchecked")
                    Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) (parameter.getType());

                    enumGeneratorNameToClassMap.merge(paramGenName, enumClass, (storedClass, currentClass) -> {
                        if (storedClass != currentClass) {
                            throw new IllegalStateException("Enum param gen with name " + paramGenName + " can't be associated with two different types: " + storedClass.getSimpleName() + " and " + currentClass.getSimpleName());
                        }
                        return storedClass;
                    });
                });

        return enumGeneratorNameToClassMap;
    }

    /**
     * Sort methods by name to make scenario generation deterministic.
     */
    private static Method[] getDeclaredMethodSorted(Class<?> clazz) {
        Method[] methods = clazz.getDeclaredMethods();
        Comparator<Method> comparator = Comparator
                // compare by method name
                .comparing(Method::getName)
                // then compare by parameter class names
                .thenComparing(m -> Arrays.stream(m.getParameterTypes()).map(Class::getName).collect(Collectors.joining(":")));
        Arrays.sort(methods, comparator);
        return methods;
    }

    private static ParameterGenerator<?> getOrCreateGenerator(Method m,
                                                              Parameter p,
                                                              String nameInOperation,
                                                              Map<String, ParameterGenerator<?>> namedGens,
                                                              Map<Class<?>, ParameterGenerator<?>> defaultGens,
                                                              RandomProvider randomProvider) {
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
            ParameterGenerator<?> defaultGenerator = defaultGens.get(p.getType());
            if (defaultGenerator != null)
                return defaultGenerator;
            if (p.getType().isEnum()) {
                @SuppressWarnings("unchecked")
                EnumGen<?> generator = createEnumGenerator("", randomProvider, (Class<? extends Enum<?>>) p.getType());
                defaultGens.put(p.getType(), generator);
                return generator;
            }
            // Cannot create default parameter generator, throw an exception
            throw new IllegalStateException("Generator for parameter \"" + p + "\" in method \""
                + m.getName() + "\" should be specified.");
        }
        // If the @Param annotation is presented check its correctness firstly
        if (!paramAnn.name().isEmpty() && !(paramAnn.gen() == DummyParameterGenerator.class))
            throw new IllegalStateException("@Param should have either name or gen with optionally configuration");
        // If @Param annotation specifies generator's name then return the specified generator
        if (!paramAnn.name().isEmpty())
            return checkAndGetNamedGenerator(namedGens, paramAnn.name());
        // Otherwise create new parameter generator
        if (p.getType().isEnum()) {
            @SuppressWarnings("unchecked")
            Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) (p.getType());

            return createEnumGenerator(paramAnn.conf(), randomProvider, enumClass);
        } else {
            return createGenerator(paramAnn, randomProvider);
        }
    }

    private static ParameterGenerator<?> createGenerator(Param paramAnn, RandomProvider randomProvider) {
        try {
            return paramAnn.gen().getConstructor(RandomProvider.class, String.class).newInstance(randomProvider, paramAnn.conf());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create parameter gen", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EnumGen<?> createEnumGenerator(String configuration, RandomProvider randomProvider, Class<? extends Enum<?>> enumClass) {
        return new EnumGen(enumClass, randomProvider, configuration);
    }

    private static Map<Class<?>, ParameterGenerator<?>> createDefaultGenerators(RandomProvider randomProvider) {
        Map<Class<?>, ParameterGenerator<?>> defaultGens = new HashMap<>();
        defaultGens.put(boolean.class, new BooleanGen(randomProvider, ""));
        defaultGens.put(Boolean.class, defaultGens.get(boolean.class));
        defaultGens.put(byte.class, new ByteGen(randomProvider, ""));
        defaultGens.put(Byte.class, defaultGens.get(byte.class));
        defaultGens.put(short.class, new ShortGen(randomProvider, ""));
        defaultGens.put(Short.class, defaultGens.get(short.class));
        defaultGens.put(int.class, new IntGen(randomProvider, ""));
        defaultGens.put(Integer.class, defaultGens.get(int.class));
        defaultGens.put(long.class, new LongGen(randomProvider, ""));
        defaultGens.put(Long.class, defaultGens.get(long.class));
        defaultGens.put(float.class, new FloatGen(randomProvider, ""));
        defaultGens.put(Float.class, defaultGens.get(float.class));
        defaultGens.put(double.class, new DoubleGen(randomProvider, ""));
        defaultGens.put(Double.class, defaultGens.get(double.class));
        defaultGens.put(String.class, new StringGen(randomProvider, ""));

        return defaultGens;
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
