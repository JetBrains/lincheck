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

import org.jetbrains.kotlinx.lincheck.runner.*;
import org.jetbrains.kotlinx.lincheck.strategy.*;
import org.jetbrains.kotlinx.lincheck.strategy.managed.*;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*;
import org.jetbrains.kotlinx.lincheck.strategy.stress.*;
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassVisitor;
import org.jetbrains.kotlinx.lincheck.transformation.TransformationMode;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.jetbrains.kotlinx.lincheck.TransformationClassLoader.*;
import static org.jetbrains.kotlinx.lincheck.UtilsKt.getCanonicalClassName;
import static org.objectweb.asm.Opcodes.*;

/**
 * This transformer applies required for {@link Strategy} and {@link Runner}
 * class transformations and hines them from others.
 */
public class TransformationClassLoader extends ExecutionClassLoader {
    public static final String REMAPPED_PACKAGE_INTERNAL_NAME = "org/jetbrains/kotlinx/lincheck/tran$f*rmed/";
    public static final String REMAPPED_PACKAGE_CANONICAL_NAME = getCanonicalClassName(REMAPPED_PACKAGE_INTERNAL_NAME);
    public static final int ASM_API = ASM9;

    private final Function<ClassVisitor, ClassVisitor> classTransformer;
    private final Remapper remapper;

    // Cache for classloading and frames computing during the transformation.
    private Map<String, Class<?>> cache = new ConcurrentHashMap<>();

    private Map<String, byte[]> bytecodeCache = null;
    private static final Map<String, byte[]> stressStrategyBytecodeCache = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> modelCheckingStrategyBytecodeCache = new ConcurrentHashMap<>();

    public TransformationClassLoader(Strategy strategy) {
        // Apply the strategy's transformer at first, then the runner's one.
        // TODO: add Strategy.transformationMode property to get rig of this instanceof check
        //  (now it's impossible due to lack of internal modifier in Java)
        TransformationMode transformationMode = strategy instanceof ModelCheckingStrategy ? TransformationMode.MODEL_CHECKING : TransformationMode.STRESS;
        classTransformer = (classVisitor) -> new LincheckClassVisitor(transformationMode, classVisitor);
        remapper = UtilsKt.getRemapperByTransformers(transformationMode);
        if (strategy instanceof StressStrategy) {
            bytecodeCache = stressStrategyBytecodeCache;
        } else if (strategy instanceof ManagedStrategy && ((ManagedStrategy) strategy).useBytecodeCache()) {
            bytecodeCache = modelCheckingStrategyBytecodeCache;
        }
    }

    public TransformationClassLoader(Function<ClassVisitor, ClassVisitor> classTransformer) {
        this.classTransformer = classTransformer;
        remapper = null;
    }

    /**
     * Returns `true` if the specified class should not be transformed.
     */
    private static boolean doNotTransform(String className) {
        if (className.startsWith(REMAPPED_PACKAGE_CANONICAL_NAME)) return false;
        if (isImpossibleToTransformApiClass(className)) return true;
        return className.startsWith("sun.") ||
               className.startsWith("java.") ||
               className.startsWith("jdk.internal.") ||
               (className.startsWith("kotlin.") &&
                   !className.startsWith("kotlin.collections.") && // transform kotlin collections
                   !(className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) && // transform kotlin iterator classes
                   !className.startsWith("kotlin.ranges.") && // transform kotlin ranges
                   !className.equals("kotlin.random.Random$Default") // transform Random.nextInt(..) and similar calls
               ) ||
               className.startsWith("com.intellij.rt.coverage.") ||
               (className.startsWith("org.jetbrains.kotlinx.lincheck.") &&
                   !className.startsWith("org.jetbrains.kotlinx.lincheck_test.")
               ) ||
               className.equals(kotlinx.coroutines.DebugKt.class.getName()) ||
               className.equals(kotlin.coroutines.CoroutineContext.class.getName()) ||
               className.equals(kotlinx.coroutines.CoroutineContextKt.class.getName()) ||
               className.equals(kotlinx.coroutines.CoroutineScope.class.getName()) ||
               className.equals(kotlinx.coroutines.CancellableContinuation.class.getName()) ||
               className.equals(kotlinx.coroutines.CoroutineExceptionHandler.class.getName()) ||
               className.equals(kotlinx.coroutines.CoroutineDispatcher.class.getName()) ||
               className.equals("kotlinx.coroutines.NotCompleted") ||
               className.equals("kotlinx.coroutines.CancelHandler") ||
               className.equals("kotlinx.coroutines.CancelHandlerBase");
    }

    /**
     * Some API classes cannot be transformed due to the [sun.reflect.CallerSensitive] annotation.
     */
    public static boolean isImpossibleToTransformApiClass(String className) {
        return className.equals("sun.misc.Unsafe") ||
                className.equals("jdk.internal.misc.Unsafe") ||
                className.equals("java.lang.invoke.VarHandle") ||
                className.startsWith("java.util.concurrent.atomic.Atomic") && className.endsWith("FieldUpdater");
    }

    /**
     * Returns `true` if the specified class should not be transformed.
     * Note that this method takes into consideration whether class name will be remapped.
     */
    boolean shouldBeTransformed(Class<?> clazz) {
        return !doNotTransform(remapClassName(clazz.getName()));
    }

    /**
     * Remaps class name if needed for transformation
     */
    public String remapClassName(String className) {
        if (remapper != null) {
            String internalName = className.replace('.', '/');
            String remappedInternalName = remapper.mapType(internalName);
            return remappedInternalName.replace('/', '.');
        }
        return className;
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
                byte[] bytes = null;
                if (bytecodeCache != null) {
                    bytes = bytecodeCache.get(name);
                }
                if (bytes == null) {
                    bytes = instrument(originalName(name));
                    if (bytecodeCache != null) {
                        bytecodeCache.put(name, bytes);
                    }
                }
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
        ClassVersionGetter infoGetter = new ClassVersionGetter();
        cr.accept(infoGetter, 0);
        ClassWriter cw = new TransformationClassWriter(infoGetter.getClassVersion(), remapper);
        ClassVisitor cv = cw;
        // The code in this comment block verifies the transformed byte-code and prints it for the specified class,
        // you may need to uncomment it for debug purposes under development.
        // cv = new CheckClassAdapter(cv, false);
        // if (className.equals(YourClass.class.getCanonicalName()))
        //   cv = new TraceClassVisitor(cv, new PrintWriter(System.out));

        cv = classTransformer.apply(cv);
        // Get transformed bytecode
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /**
     * Returns the original name of the specified class before transformation.
     */
    private String originalName(String className) {
        if (className.startsWith(REMAPPED_PACKAGE_CANONICAL_NAME))
            return className.substring(REMAPPED_PACKAGE_CANONICAL_NAME.length());
        return className;
    }

    @Override
    public URL getResource(String name) {
        return super.getResource(name);
    }
}

/**
 * ClassWriter for the classes transformed by *lincheck* with a correct
 * {@link ClassWriter#getCommonSuperClass} implementation.
 */
class TransformationClassWriter extends ClassWriter {
    private final Remapper remapper;

    public TransformationClassWriter(int classVersion, Remapper remapper) {
        super(classVersion > V1_6 ? COMPUTE_FRAMES : COMPUTE_MAXS);
        this.remapper = remapper;
    }

    /**
     * ASM uses `Class.forName` for given types, however it can lead to cyclic dependencies when loading transformed classes.
     * Thus, we call the original method for non-transformed class names and then just fix it if needed.
     */
    @Override
    protected String getCommonSuperClass(final String type1, final String type2) {
        String result = super.getCommonSuperClass(originalInternalName(type1), originalInternalName(type2));
        if (remapper != null)
            return remapper.map(result);
        return result;
    }

    /**
     * Returns the name of the specified class before it was transformed.
     *
     * Classes from `java.util` package are moved to [TRANSFORMED_PACKAGE_NAME] during transformation,
     * this method changes the package to the original one.
     */
    private String originalInternalName(String internalName) {
        if (internalName.startsWith(REMAPPED_PACKAGE_INTERNAL_NAME))
            return internalName.substring(REMAPPED_PACKAGE_INTERNAL_NAME.length());
        return internalName;
    }
}

/**
 * Visitor for retrieving information of class version needed for making a choice between COMPUTE_FRAMES and COMPUTE_MAXS.
 * COMPUTE_FRAMES implies COMPUTE_MAXS, but is more expensive, so it is used only for classes with version 1.7 or higher.
 */
class ClassVersionGetter extends ClassVisitor {
    private int classVersion;

    public ClassVersionGetter() {
        super(TransformationClassLoader.ASM_API);
    }

    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.classVersion = version;
    }

    public int getClassVersion() {
        return classVersion;
    }
}