package com.devexperts.dxlab.lincheck;

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

import com.devexperts.dxlab.lincheck.runner.Runner;
import com.devexperts.dxlab.lincheck.strategy.Strategy;
import com.devexperts.jagent.ClassInfo;
import com.devexperts.jagent.ClassInfoCache;
import com.devexperts.jagent.ClassInfoVisitor;
import com.devexperts.jagent.FrameClassWriter;
import com.devexperts.jagent.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ClassInfoCache ciCache = new ClassInfoCache(new Log("lin-check", Log.Level.DEBUG, null));

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
        return className == null ||
            (className.startsWith("com.devexperts.dxlab.lincheck.") &&
                !className.startsWith("com.devexperts.dxlab.lincheck.test.") &&
                !className.equals("com.devexperts.dxlab.lincheck.strategy.ManagedStrategyHolder")) ||
            className.startsWith("sun.") ||
            className.startsWith("java.");
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
        // Build class info
        ClassInfoVisitor ciVisitor = new ClassInfoVisitor();
        cr.accept(ciVisitor, 0);
        ClassInfo ci = ciVisitor.buildClassInfo();
        // Construct transformation pipeline:
        // apply the strategy's transformer at first,
        // then the runner's one.
        ClassWriter cw = new FrameClassWriter(this, ciCache, ci.getVersion());
        ClassVisitor cv = new CheckClassAdapter(cw, false); // for debug
        if (runner.needsTransformation()) {
            cv = runner.createTransformer(cv, ci);
        }
        if (strategy.needsTransformation()) {
            cv = strategy.createTransformer(cv, ci);
        }
        // Get transformed bytecode
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    @Override
    public URL getResource(String name) {
        return super.getResource(name);
    }
}
