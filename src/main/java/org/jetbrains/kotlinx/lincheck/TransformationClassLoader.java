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

import org.jetbrains.kotlinx.lincheck.runner.Runner;
import org.jetbrains.kotlinx.lincheck.strategy.Strategy;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * This transformer applies required for {@link Strategy} and {@link Runner}
 * class transformations and hines them from others.
 */
public class TransformationClassLoader extends ExecutionClassLoader {
    private final List<Function<ClassVisitor, ClassVisitor>> classTransformers;
    // Cache for classloading and frames computing during the transformation
    private final Map<String, Class<?>> cache = new ConcurrentHashMap<>();

    public TransformationClassLoader(Strategy strategy, Runner runner) {
        classTransformers = new ArrayList<>();
        // apply the strategy's transformer at first, then the runner's one.
        if (strategy.needsTransformation()) classTransformers.add(strategy::createTransformer);
        if (runner.needsTransformation()) classTransformers.add(runner::createTransformer);
    }

    public TransformationClassLoader(Function<ClassVisitor, ClassVisitor> classTransformer) {
        this.classTransformers = Collections.singletonList(classTransformer);
    }

    /**
     * Check if class should not be transformed
     *
     * @param className checking class name
     * @return result of checking class
     */
    private static boolean doNotTransform(String className) {
        return className == null ||
            (className.startsWith("org.jetbrains.kotlinx.lincheck.") &&
                !className.startsWith("org.jetbrains.kotlinx.lincheck.test.") &&
                !className.endsWith("ManagedStrategyHolder")) ||
            className.startsWith("sun.") ||
            className.startsWith("java.") ||
            className.startsWith("jdk.internal.") ||
            className.startsWith("kotlin.") ||
            className.equals("kotlinx.coroutines.CancellableContinuation") ||
            className.equals("kotlinx.coroutines.CoroutineDispatcher");
        // TODO let's transform java.util.concurrent
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> result = cache.get(name);
            if (result != null) {
                return result;
            }
            if (doNotTransform(name)) {
                result = super.loadClass(name);
                cache.put(name, result);
                return result;
            }
            try {
                byte[] bytes = instrument(name);
                result = defineClass(name, bytes, 0, bytes.length);
                cache.put(name, result);
                return result;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot transform class " + name, e);
            }
        }
    }

    /**
     * Reads class as resource, instruments it (applies {@link Strategy}'s transformer at first,
     * then {@link Runner}'s) and returns the resulting byte-code.
     *
     * @param className name of the class to be transformed.
     * @return the byte-code of the transformed class.
     * @throws IOException if class could not be read as a resource.
     */
    private byte[] instrument(String className) throws IOException {
        // Create ClassReader
        ClassReader cr = new ClassReader(className);
        // Construct transformation pipeline:
        // apply the strategy's transformer at first,
        // then the runner's one.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ClassVisitor cv = new CheckClassAdapter(cw, false); // for debug
        for (Function<ClassVisitor, ClassVisitor> ct : classTransformers)
            cv = ct.apply(cv);
        // Get transformed bytecode
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    @Override
    public URL getResource(String name) {
        return super.getResource(name);
    }
}
