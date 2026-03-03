/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Registry for managing breakpoint condition factories.
 * <p>
 * This class handles the registration and retrieval of condition factories for breakpoints
 * with conditions. It maintains a cache of factories keyed by (className, lineNumber, classLoaderId)
 * and automatically cleans up factories when their associated class loaders are garbage collected.
 */
public class BreakpointConditionRegistry {

    /**
     * Key for caching condition factories.
     */
    private static class ConditionFactoryKey {
        private final String className;
        private final int lineNumber;
        private final String classLoaderId;

        // We need to store this weak reference somewhere to subscribe for class loader collection by GC.
        @SuppressWarnings("all")
        private final WeakReference<ClassLoader> classLoaderRef;

        public ConditionFactoryKey(String className, int lineNumber, String classLoaderId) {
            this(className, lineNumber, classLoaderId, null);
        }

        public ConditionFactoryKey(String className, int lineNumber, String classLoaderId, WeakReference<ClassLoader> classLoaderRef) {
            this.className = className;
            this.lineNumber = lineNumber;
            this.classLoaderId = classLoaderId;
            this.classLoaderRef = classLoaderRef;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ConditionFactoryKey)) return false;
            ConditionFactoryKey other = (ConditionFactoryKey) obj;
            return lineNumber == other.lineNumber && className.equals(other.className) && classLoaderId.equals(other.classLoaderId);
        }

        @Override
        public int hashCode() {
            int result = className.hashCode();
            result = 31 * result + lineNumber;
            result = 31 * result + classLoaderId.hashCode();
            return result;
        }
    }

    /**
     * Cache of condition factories: (className, lineNumber, classLoaderId) -> Function<Object[], BooleanSupplier>
     */
    private static final ConcurrentHashMap<ConditionFactoryKey, Function<Object[], BooleanSupplier>> conditionFactories =
        new ConcurrentHashMap<>();

    /**
     * Reference queue for tracking garbage collected class loaders.
     */
    private static final ReferenceQueue<ClassLoader> classLoaderReferenceQueue =
        new ReferenceQueue<>();

    /**
     * Thread that monitors for garbage collected class loaders and cleans up their factories.
     */
    private static final Thread classLoaderCleanupThread;

    static {
        classLoaderCleanupThread = new Thread(() -> {
            while (true) {
                try {
                    // Wait for a class loader to be garbage collected
                    Reference<?> ref = classLoaderReferenceQueue.remove();
                    Object classLoader = ref.get();
                    if (classLoader instanceof ClassLoader) {
                        String classLoaderIdToRemove = getClassLoaderId((ClassLoader) classLoader);
                        conditionFactories.entrySet().removeIf(entry ->
                                entry.getKey().classLoaderId.equals(classLoaderIdToRemove)
                        );
                    }
                } catch (InterruptedException e) {
                    // Thread interrupted, exit
                    break;
                } catch (Exception e) {
                    // Log and continue
                    e.printStackTrace();
                }
            }
        }, "LiveDebugger-Condition-ClassLoader-Cleanup");
        classLoaderCleanupThread.setDaemon(true);
        classLoaderCleanupThread.start();
    }

    /**
     * Registers a condition factory for a breakpoint.
     * Should be called once per (className, lineNumber, classLoader) to register the factory.
     *
     * @param className     the fully qualified name of the condition class
     * @param lineNumber    the line number of the breakpoint
     * @param classLoaderId identifier for the class loader (e.g., class loader's identity hash code)
     * @param factory       the factory function that creates BooleanSupplier instances
     * @param classLoader   the class loader instance (used for tracking garbage collection)
     */
    public static void registerConditionFactory(String className, int lineNumber, String classLoaderId, Function<Object[], BooleanSupplier> factory, ClassLoader classLoader) {
        WeakReference<ClassLoader> classLoaderRef = new WeakReference<>(classLoader, classLoaderReferenceQueue);
        ConditionFactoryKey key = new ConditionFactoryKey(className, lineNumber, classLoaderId, classLoaderRef);
        conditionFactories.putIfAbsent(key, factory);
    }

    /**
     * Creates a BooleanSupplier instance for a breakpoint condition.
     * Uses the previously registered factory for the given (className, lineNumber, classLoader) key.
     *
     * @param className     the fully qualified name of the condition class
     * @param lineNumber    the line number of the breakpoint
     * @param classLoaderId identifier for the class loader (e.g., class loader's identity hash code)
     * @param args          the captured local variable values to pass to the condition
     * @return a BooleanSupplier instance that evaluates the condition
     * @throws IllegalStateException if no factory has been registered for the given key
     */
    public static BooleanSupplier createConditionInstance(String className, int lineNumber, String classLoaderId, Object[] args) {
        ConditionFactoryKey key = new ConditionFactoryKey(className, lineNumber, classLoaderId);
        Function<Object[], BooleanSupplier> factory = conditionFactories.get(key);

        if (factory == null) {
            throw new IllegalStateException("No factory registered for condition: " + className + " at line " + lineNumber);
        }

        // Use the factory to create a new BooleanSupplier instance
        return factory.apply(args);
    }

    public static String getClassLoaderId(ClassLoader loader) {
        return String.valueOf(System.identityHashCode(loader));
    }

    public static void clear() {
        conditionFactories.clear();
    }
}
