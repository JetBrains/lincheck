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
import com.devexperts.jagent.ClassInfo;
import com.devexperts.jagent.ClassInfoCache;
import com.devexperts.jagent.ClassInfoVisitor;
import com.devexperts.jagent.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.jetbrains.kotlinx.lincheck.TransformationClassLoader.*;

/**
 * This transformer applies required for {@link Strategy} and {@link Runner}
 * class transformations and hines them from others.
 */
public class TransformationClassLoader extends ExecutionClassLoader {
    // Strategy and runner provide class transformers
    private final Strategy strategy;
    private final Runner runner;
    // Cache for classloading and frames computing during the transformation
    private final Map<String, Class<?>> cache = new ConcurrentHashMap<>();

    public static final String JAVA_UTIL_PACKAGE = "java/util/";
    public static final String TRANSFORMED_PACKAGE = "org/jetbrains/kotlinx/lincheck/transformed/";
    public static final String TRANSFORMED_POINTED_PACKAGE = TRANSFORMED_PACKAGE.replaceAll("/", ".");

    public TransformationClassLoader(Strategy strategy, Runner runner) {
        this.strategy = strategy;
        this.runner = runner;
    }

    /**
     * Check if class should not be transformed
     *
     * @param className checking class name
     * @return result of checking class
     */
    private static boolean doNotTransform(String className) {
        if (className.startsWith(TRANSFORMED_POINTED_PACKAGE)) return false;
        return className == null ||
            (
                className.startsWith("org.jetbrains.kotlinx.lincheck.") &&
                !className.startsWith("org.jetbrains.kotlinx.lincheck.test.") &&
                !className.startsWith("org.jetbrains.kotlinx.lincheck.tests.") &&
                !className.equals("org.jetbrains.kotlinx.lincheck.strategy.ManagedStrategyHolder")
            ) ||
            (
                className.startsWith("kotlin.") &&
                !className.startsWith("kotlin.collections.")
            ) ||
            className.startsWith("sun.") ||
            className.startsWith("java.") ||
            className.startsWith("jdk.internal.");
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
                byte[] bytes = instrument(originalPointedName(name));
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
        ClassWriter cw = new TransformationClassWriter(ClassWriter.COMPUTE_FRAMES, this);
        ClassVisitor cv = new CheckClassAdapter(cw, false); // for debug
        if (runner.needsTransformation()) {
            cv = runner.createTransformer(cv);
        }
        if (strategy.needsTransformation()) {
            cv = strategy.createTransformer(cv);
        }
        // Get transformed bytecode
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /**
     * Returns name of class the moment before it was transformed
     */
    static String originalName(String className) {
        if (className.startsWith(TRANSFORMED_PACKAGE)) {
            return className.substring(TRANSFORMED_PACKAGE.length());
        }
        return className;
    }

    /**
     * Returns name of class the moment before it was transformed
     */
    private String originalPointedName(String className) {
        if (className.startsWith(TRANSFORMED_POINTED_PACKAGE)) {
            return className.substring(TRANSFORMED_POINTED_PACKAGE.length());
        }
        return className;
    }
}

/**
 * ClassWriter for classes transformed by LinCheck.
 * Overrides getCommonSuperClass method so that it could work correctly for classes transformed by LinCheck.
 */
class TransformationClassWriter extends ClassWriter {
    private ClassLoader loader;

    public TransformationClassWriter(int flags, ClassLoader loader) {
        super(flags);
        this.loader = loader;
    }

    protected ClassLoader getClassLoader() {
        return loader;
    }

    /**
     * ASM uses Class.forName for given types, however it can lead to cyclic dependencies when loading transformed classes.
     * Thus, we call original method for not-transformed class names and then just fix it if needed.
     */
    @Override
    protected String getCommonSuperClass(final String type1, final String type2) {
        String result = super.getCommonSuperClass(originalName(type1), originalName(type2));
        if (result.startsWith(JAVA_UTIL_PACKAGE))
            return TRANSFORMED_PACKAGE + result;
        return result;
    }
}
