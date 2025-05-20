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

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.paramgen.*;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.Validate;

import static org.jetbrains.kotlinx.lincheck.ActorKt.*;

import java.lang.reflect.*;
import java.util.stream.*;
import java.util.*;

/**
 * Contains information about the provided operations.
 */
@SuppressWarnings("removal")
public class CTestStructure {
    public final List<ActorGenerator> actorGenerators;
    public final List<ParameterGenerator<?>> parameterGenerators;
    public final List<OperationGroup> operationGroups;

    @Nullable
    public final Actor validationFunction;
    public final Method stateRepresentation;

    public final RandomProvider randomProvider;

    private CTestStructure(
            List<ActorGenerator> actorGenerators,
            List<ParameterGenerator<?>> parameterGenerators,
            List<OperationGroup> operationGroups,
            @Nullable Actor validationFunction,
            Method stateRepresentation,
            RandomProvider randomProvider
    ) {
        this.actorGenerators = actorGenerators;
        this.parameterGenerators = parameterGenerators;
        this.operationGroups = operationGroups;
        this.validationFunction = validationFunction;
        this.stateRepresentation = stateRepresentation;
        this.randomProvider = randomProvider;
    }

    /**
     * Constructs {@link CTestStructure} for the specified test class.
     */
    public static CTestStructure getFromTestClass(Class<?> testClass) {
        Map<String, OperationGroup> groupConfigs = new HashMap<>();
        List<ActorGenerator> actorGenerators = new ArrayList<>();
        List<Actor> validationFunctions = new ArrayList<>();
        List<Method> stateRepresentations = new ArrayList<>();
        Class<?> clazz = testClass;
        RandomProvider randomProvider = new RandomProvider();
        Map<Class<?>, ParameterGenerator<?>> parameterGeneratorsMap = new HashMap<>();

        while (clazz != null) {
            readTestStructureFromClass(
                    clazz,
                    groupConfigs,
                    actorGenerators,
                    parameterGeneratorsMap,
                    validationFunctions,
                    stateRepresentations,
                    randomProvider
            );
            clazz = clazz.getSuperclass();
        }

        List<ParameterGenerator<?>> parameterGenerators = new ArrayList<>(parameterGeneratorsMap.values());

        if (stateRepresentations.size() > 1) {
            throw new IllegalStateException("At most one state representation function is allowed, but several were detected:" +
                stateRepresentations.stream()
                        .map(Method::getName)
                        .collect(Collectors.joining(", ")));
        }
        Method stateRepresentation = stateRepresentations.isEmpty() ? null : stateRepresentations.get(0);

        if (validationFunctions.size() > 1) {
            throw new IllegalStateException("At most one validation function is allowed, but several were detected: " +
                validationFunctions.stream()
                        .map(actor -> {
                            Method method = actor.getMethod();
                            return method.getName();
                        })
                        .collect(Collectors.joining(", ")));
        }
        Actor validationFunction = validationFunctions.isEmpty() ? null : validationFunctions.get(0);

        return new CTestStructure(
                actorGenerators,
                parameterGenerators,
                new ArrayList<>(groupConfigs.values()),
                validationFunction,
                stateRepresentation,
                randomProvider
        );
    }

    private static void readTestStructureFromClass(
            Class<?> clazz,
            Map<String, OperationGroup> groupConfigs,
            List<ActorGenerator> actorGenerators,
            Map<Class<?>, ParameterGenerator<?>> parameterGeneratorsMap,
            List<Actor> validationFunctions,
            List<Method> stateRepresentations,
            RandomProvider randomProvider
    ) {
        // Read named parameter generators (declared for class)
        Map<String, ParameterGenerator<?>> namedGens = createNamedGens(clazz, randomProvider);
        // Create map for default (not named) gens
        Map<Class<?>, ParameterGenerator<?>> defaultGens = createDefaultGenerators(randomProvider);
        // Read group configurations
        for (org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig opGroupConfigAnn :
             clazz.getAnnotationsByType(org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig.class))
        {
            groupConfigs.put(
                opGroupConfigAnn.name(),
                new OperationGroup(opGroupConfigAnn.name(), opGroupConfigAnn.nonParallel())
            );
        }

        // Process class methods
        for (Method m : getDeclaredMethodSorted(clazz)) {
            // Operation
            OperationConfig opConfig = parseOperationAnnotation(m);
            if (opConfig != null) {
                boolean isSuspendableMethod = isSuspendable(m);
                // Check that params() in @Operation is empty or has the same size as the method
                if (opConfig.getParams().length > 0 && opConfig.getParams().length != m.getParameterCount()) {
                    throw new IllegalArgumentException("Invalid count of parameter generators for " + m);
                }
                // Construct a list of parameter generators
                final List<ParameterGenerator<?>> gens = new ArrayList<>();
                int nParameters = m.getParameterCount() - (isSuspendableMethod ? 1 : 0);
                for (int i = 0; i < nParameters; i++) {
                    String nameInOperation = opConfig.getParams().length > 0 ? opConfig.getParams()[i] : null;
                    Parameter parameter = m.getParameters()[i];
                    ParameterGenerator<?> parameterGenerator = getOrCreateGenerator(m,
                            parameter,
                            nameInOperation,
                            namedGens,
                            defaultGens,
                            randomProvider
                    );
                    parameterGeneratorsMap.putIfAbsent(parameter.getType(), parameterGenerator);
                    gens.add(parameterGenerator);
                }
                ActorGenerator actorGenerator = new ActorGenerator(m, gens, opConfig.isRunOnce(),
                    opConfig.isCancellableOnSuspension(), opConfig.isBlocking(), opConfig.isCausesBlocking(),
                    opConfig.isPromptCancellation());
                actorGenerators.add(actorGenerator);
                // Get list of groups and add this operation to specified ones
                String opGroup = opConfig.getGroup();
                if (!opGroup.isEmpty()) {
                    OperationGroup operationGroup = groupConfigs.get(opGroup);
                    if (operationGroup == null)
                        throw new IllegalStateException("Operation group " + opGroup + " is not configured");
                    operationGroup.actors.add(actorGenerator);
                }
                String opNonParallelGroupName = opConfig.getNonParallelGroup();
                if (!opNonParallelGroupName.isEmpty()) { // is `nonParallelGroup` specified?
                    groupConfigs.computeIfAbsent(opNonParallelGroupName, name -> new OperationGroup(name, true));
                    groupConfigs.get(opNonParallelGroupName).actors.add(actorGenerator);
                }
            }

            ValidateConfig validateConfig = parseValidateAnnotation(m);
            if (validateConfig != null) {
                if (m.getParameterCount() != 0)
                    throw new IllegalStateException("Validation function " + m.getName() + " should not have parameters");
                validationFunctions.add(new Actor(m, Collections.emptyList()));
            }

            StateRepresentationConfig stateRepConfig = parseStateRepresentationAnnotation(m);
            if (stateRepConfig != null) {
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
        List<ParamConfig> paramConfigs = getParamConfigsFromClass(clazz);
        for (ParamConfig paramConfig : paramConfigs) {
            // Throw exception if a user tried to declare EnumGen on the top of the class but without a name
            if (paramConfig.getName().isEmpty()) {
                throw new IllegalArgumentException("@Param name in class declaration cannot be empty");
            }
            if (enumGeneratorNameToClassMap.containsKey(paramConfig.getName())) {
                Class<? extends Enum<?>> enumClass = enumGeneratorNameToClassMap.get(paramConfig.getName());
                namedGens.put(paramConfig.getName(), createEnumGenerator(paramConfig.getConf(), randomProvider, enumClass));
            } else {
                namedGens.put(paramConfig.getName(), createGenerator(paramConfig, randomProvider));
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
                .filter(method -> isOperationAnnotationPresent(method)) // take methods, annotated as @Operation
                .flatMap(method -> Arrays.stream(method.getParameters())) // get their parameters
                .filter(parameter -> parameter.getType().isEnum() && // which are enums
                                     isParamAnnotationPresent(parameter) && // annotated with @Param
                                     !getParamConfigsByType(parameter).get(0).getName().isEmpty()) // and references to some named EnumGen
                .forEach(parameter -> {
                    String paramGenName = getParamConfigsByType(parameter).get(0).getName();

                    @SuppressWarnings("unchecked")
                    Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) (parameter.getType());

                    enumGeneratorNameToClassMap.merge(paramGenName, enumClass, (storedClass, currentClass) -> {
                        if (storedClass != currentClass) {
                            throw new IllegalStateException("Enum param gen with name " + paramGenName +
                                " can't be associated with two different types: " + storedClass.getSimpleName() +
                                " and " + currentClass.getSimpleName());
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
                .thenComparing(m -> Arrays.stream(m.getParameterTypes())
                        .map(Class::getName)
                        .collect(Collectors.joining(":"))
                );
        Arrays.sort(methods, comparator);
        return methods;
    }

    private static ParameterGenerator<?> getOrCreateGenerator(
            Method m,
            Parameter p,
            String nameInOperation,
            Map<String, ParameterGenerator<?>> namedGens,
            Map<Class<?>, ParameterGenerator<?>> defaultGens,
            RandomProvider randomProvider
    ) {
        // Parse @Param annotation on the parameter
        ParamConfig paramConfig = parseParamAnnotationFromParameter(p);
        // If this annotation not presented use named generator based on name presented in @Operation or parameter name.
        if (paramConfig == null) {
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
        if (!paramConfig.getName().isEmpty() && !(paramConfig.getGen() == DummyParameterGenerator.class))
            throw new IllegalStateException("@Param should have either name or gen with optionally configuration");
        // If @Param annotation specifies generator's name then return the specified generator
        if (!paramConfig.getName().isEmpty())
            return checkAndGetNamedGenerator(namedGens, paramConfig.getName());
        // Otherwise create new parameter generator
        if (p.getType().isEnum()) {
            @SuppressWarnings("unchecked")
            Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) (p.getType());

            return createEnumGenerator(paramConfig.getConf(), randomProvider, enumClass);
        } else {
            return createGenerator(paramConfig, randomProvider);
        }
    }

    private static ParameterGenerator<?> createGenerator(ParamConfig paramConfig, RandomProvider randomProvider) {
        try {
            return paramConfig.getGen().getConstructor(RandomProvider.class, String.class).newInstance(randomProvider, paramConfig.getConf());
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

    /**
     * Checks if this test structure has any operations.
     *
     * @return true if there are any operations, false otherwise
     */
    public boolean hasOperations() {
        return !actorGenerators.isEmpty();
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

    /**
     * POJO class representing the Operation annotation.
     */
    private static class OperationConfig {
        private final String[] params;
        private final boolean runOnce;
        private final String group;
        private final String nonParallelGroup;
        private final Class<? extends Throwable>[] handleExceptionsAsResult;
        private final boolean cancellableOnSuspension;
        private final boolean allowExtraSuspension;
        private final boolean blocking;
        private final boolean causesBlocking;
        private final boolean promptCancellation;

        OperationConfig(
            String[] params,
            boolean runOnce,
            String group,
            String nonParallelGroup,
            Class<? extends Throwable>[] handleExceptionsAsResult,
            boolean cancellableOnSuspension,
            boolean allowExtraSuspension,
            boolean blocking,
            boolean causesBlocking,
            boolean promptCancellation
        ) {
            this.params = params;
            this.runOnce = runOnce;
            this.group = group;
            this.nonParallelGroup = nonParallelGroup;
            this.handleExceptionsAsResult = handleExceptionsAsResult;
            this.cancellableOnSuspension = cancellableOnSuspension;
            this.allowExtraSuspension = allowExtraSuspension;
            this.blocking = blocking;
            this.causesBlocking = causesBlocking;
            this.promptCancellation = promptCancellation;
        }

        public String[] getParams() {
            return params;
        }

        public boolean isRunOnce() {
            return runOnce;
        }

        public String getGroup() {
            return group;
        }

        public String getNonParallelGroup() {
            return nonParallelGroup;
        }

        public Class<? extends Throwable>[] getHandleExceptionsAsResult() {
            return handleExceptionsAsResult;
        }

        public boolean isCancellableOnSuspension() {
            return cancellableOnSuspension;
        }

        public boolean isAllowExtraSuspension() {
            return allowExtraSuspension;
        }

        public boolean isBlocking() {
            return blocking;
        }

        public boolean isCausesBlocking() {
            return causesBlocking;
        }

        public boolean isPromptCancellation() {
            return promptCancellation;
        }
    }

    private static OperationConfig parseOperationAnnotation(Method m) {
        if (!isOperationAnnotationPresent(m)) {
            return null;
        }
        if (m.isAnnotationPresent(org.jetbrains.kotlinx.lincheck.annotations.Operation.class)) {
            org.jetbrains.kotlinx.lincheck.annotations.Operation opAnn = m.getAnnotation(org.jetbrains.kotlinx.lincheck.annotations.Operation.class);
            return parseOperationAnnotation(opAnn);
        }
        if (m.isAnnotationPresent(Operation.class)) {
            Operation opAnn = m.getAnnotation(Operation.class);
            return parseOperationAnnotation(opAnn);
        }
        return null;
    }

    private static OperationConfig parseOperationAnnotation(org.jetbrains.kotlinx.lincheck.annotations.Operation opAnn) {
        return new OperationConfig(
            opAnn.params(),
            opAnn.runOnce(),
            opAnn.group(),
            opAnn.nonParallelGroup(),
            opAnn.handleExceptionsAsResult(),
            opAnn.cancellableOnSuspension(),
            opAnn.allowExtraSuspension(),
            opAnn.blocking(),
            opAnn.causesBlocking(),
            opAnn.promptCancellation()
        );
    }

    private static OperationConfig parseOperationAnnotation(Operation opAnn) {
        return new OperationConfig(
            opAnn.params(),
            opAnn.runOnce(),
            opAnn.group(),
            opAnn.nonParallelGroup(),
            opAnn.handleExceptionsAsResult(),
            opAnn.cancellableOnSuspension(),
            opAnn.allowExtraSuspension(),
            opAnn.blocking(),
            opAnn.causesBlocking(),
            opAnn.promptCancellation()
        );
    }

    private static boolean isOperationAnnotationPresent(Method m) {
        return m.isAnnotationPresent(org.jetbrains.kotlinx.lincheck.annotations.Operation.class) ||
               m.isAnnotationPresent(Operation.class);
    }

    /**
     * POJO class representing the Param annotation.
     */
    private static class ParamConfig {
        private final String name;
        private final Class<? extends ParameterGenerator<?>> gen;
        private final String conf;

        ParamConfig(
            String name,
            Class<? extends ParameterGenerator<?>> gen,
            String conf
        ) {
            this.name = name;
            this.gen = gen;
            this.conf = conf;
        }

        public String getName() {
            return name;
        }

        public Class<? extends ParameterGenerator<?>> getGen() {
            return gen;
        }

        public String getConf() {
            return conf;
        }
    }

    private static ParamConfig parseParamAnnotation(org.jetbrains.kotlinx.lincheck.annotations.Param paramAnn) {
        return new ParamConfig(
            paramAnn.name(),
            paramAnn.gen(),
            paramAnn.conf()
        );
    }

    private static ParamConfig parseParamAnnotation(Param paramAnn) {
        return new ParamConfig(
            paramAnn.name(),
            paramAnn.gen(),
            paramAnn.conf()
        );
    }

    private static ParamConfig parseParamAnnotationFromParameter(Parameter p) {
        if (!isParamAnnotationPresent(p)) {
            return null;
        }
        if (p.isAnnotationPresent(org.jetbrains.kotlinx.lincheck.annotations.Param.class)) {
            org.jetbrains.kotlinx.lincheck.annotations.Param paramAnn = p.getAnnotation(org.jetbrains.kotlinx.lincheck.annotations.Param.class);
            return parseParamAnnotation(paramAnn);
        }
        if (p.isAnnotationPresent(Param.class)) {
            Param paramAnn = p.getAnnotation(Param.class);
            return parseParamAnnotation(paramAnn);
        }
        return null;
    }

    private static List<ParamConfig> getParamConfigsFromClass(Class<?> clazz) {
        List<ParamConfig> paramConfigs = new ArrayList<>();
        for (org.jetbrains.kotlinx.lincheck.annotations.Param paramAnn :
             clazz.getAnnotationsByType(org.jetbrains.kotlinx.lincheck.annotations.Param.class))
        {
            paramConfigs.add(parseParamAnnotation(paramAnn));
        }
        for (Param paramAnn :
             clazz.getAnnotationsByType(Param.class))
        {
            paramConfigs.add(parseParamAnnotation(paramAnn));
        }
        return paramConfigs;
    }

    private static List<ParamConfig> getParamConfigsByType(Parameter p) {
        ArrayList<ParamConfig> paramConfigs = new ArrayList<>();
        for (org.jetbrains.kotlinx.lincheck.annotations.Param param :
             p.getAnnotationsByType(org.jetbrains.kotlinx.lincheck.annotations.Param.class))
        {
            paramConfigs.add(parseParamAnnotation(param));
        }
        for (Param param :
             p.getAnnotationsByType(Param.class))
        {
            paramConfigs.add(parseParamAnnotation(param));
        }
        return paramConfigs;
    }

    private static boolean isParamAnnotationPresent(Parameter p) {
        return p.isAnnotationPresent(org.jetbrains.kotlinx.lincheck.annotations.Param.class) ||
               p.isAnnotationPresent(Param.class);
    }

    /**
     * POJO class representing the StateRepresentation annotation.
     * Since StateRepresentation is a marker annotation with no properties,
     * this class is empty.
     */
    private static class StateRepresentationConfig {}

    private static StateRepresentationConfig parseStateRepresentationAnnotation(Method m) {
        if (!isStateRepresentationAnnotationPresent(m)) {
            return null;
        }
        return new StateRepresentationConfig();
    }

    private static boolean isStateRepresentationAnnotationPresent(Method m) {
        return m.isAnnotationPresent(org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation.class);
    }

    /**
     * POJO class representing the Validate annotation.
     * Since Validate is a marker annotation with no properties,
     * this class is empty.
     */
    private static class ValidateConfig {}

    private static ValidateConfig parseValidateAnnotation(Method m) {
        if (!isValidateAnnotationPresent(m)) {
            return null;
        }
        return new ValidateConfig();
    }

    private static boolean isValidateAnnotationPresent(Method m) {
        return m.isAnnotationPresent(org.jetbrains.kotlinx.lincheck.annotations.Validate.class) ||
               m.isAnnotationPresent(Validate.class);
    }
}
