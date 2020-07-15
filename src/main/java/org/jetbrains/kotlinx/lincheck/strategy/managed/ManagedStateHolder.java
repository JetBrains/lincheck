package org.jetbrains.kotlinx.lincheck.strategy.managed;

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

import java.lang.reflect.*;
import java.util.Random;

import static org.jetbrains.kotlinx.lincheck.TransformationClassLoader.TRANSFORMED_PACKAGE_NAME;

/**
 * This utility class helps to hold the current strategy and its state. In order to run several tests in parallel,
 * every iteration has to use its own class loader, thus this holder is not shared between them.
 */
public class ManagedStateHolder {
    public static ManagedStrategy strategy;
    public static LocalObjectManager objectManager;
    public static Random random;
    private static final long INITIAL_SEED = 1337;

    /**
     * Sets the specified strategy and its initial state in the specified class loader.
     */
    public static void setState(ClassLoader loader, ManagedStrategy strategy) {
        try {
            Class<?> clazz = loader.loadClass(ManagedStateHolder.class.getCanonicalName());
            clazz.getField("strategy").set(null, strategy);
            clazz.getField("objectManager").set(null, new LocalObjectManager());
            // load transformed java.util.Random class
            Class<?> randomClass = loader.loadClass(TRANSFORMED_PACKAGE_NAME + Random.class.getCanonicalName());
            clazz.getField("random").set(null, randomClass.getConstructor().newInstance());
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException | NoSuchMethodException | InstantiationException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot set state to ManagedStateHolder", e);
        }
    }

    /**
     * Prepare state for the new invocation.
     */
    public static void resetState(ClassLoader loader) {
        try {
            Class<?> clazz = loader.loadClass(ManagedStateHolder.class.getCanonicalName());
            clazz.getMethod("resetStateImpl").invoke(null);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot set state to ManagedStateHolder", e);
        }
    }

    @SuppressWarnings("unused")
    public static void resetStateImpl() {
        random.setSeed(INITIAL_SEED);
        objectManager.reset();
    }
}