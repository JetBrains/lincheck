package org.jetbrains.kotlinx.lincheck.strategy;

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

/**
 * This utility class helps to hold the current strategy. In order to run several tests in parallel,
 * every iteration have to use its own class loader, thus this holder is not shared between them.
 */
public class ManagedStrategyHolder {
    public static ManagedStrategy strategy;

    /**
     * Sets the specified strategy in the specified class loader.
     */
    public static void setStrategy(ClassLoader loader, ManagedStrategy strategy) {
        try {
            Class<?> clazz = loader.loadClass(ManagedStrategyHolder.class.getCanonicalName());
            clazz.getField("strategy").set(null, strategy);
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            throw new IllegalStateException("Cannot set strategy to ManagedStrategyHolder", e);
        }
    }
}