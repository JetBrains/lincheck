package com.devexperts.dxlab.lincheck;

/*
 * #%L
 * core
 * %%
 * Copyright (C) 2015 - 2017 Devexperts, LLC
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

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.IdentityHashMap;

public class Utils {
    private static volatile int consumedCPU = (int) System.currentTimeMillis();

    /**
     * Busy wait, used by stress strategy.
     */
    public static void consumeCPU(int tokens) {
        int t = consumedCPU; // volatile read
        for (int i = tokens; i > 0; i--)
            t += (t * 0x5DEECE66DL + 0xBL + i) & (0xFFFFFFFFFFFFL);
        if (t == 42)
            consumedCPU += t;
    }


    /**
     * Invokes reset method on specified instance
     */
    public static void invokeReset(Method resetMethod, Object testInstance) {
        try {
            resetMethod.invoke(testInstance);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Unable to call method annotated with @Reset", e);
        }
    }

    /**
     * Creates a deep copy of specified object using {@link Unsafe} and {@link java.lang.reflect reflection}.
     */
    public static <T> T deepCopy(T origObj) {
        Cloner cloner = new Cloner();
        try {
            return cloner.clone(origObj);
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Cloner {
        private static final Unsafe UNSAFE;

        private final IdentityHashMap<Object, Object> map = new IdentityHashMap<>();

        static {
            try {
                Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                UNSAFE = (Unsafe) unsafeField.get(null);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        <T> T clone(T origObj) throws InstantiationException {
            T newObj = (T) UNSAFE.allocateInstance(origObj.getClass());
            map.put(origObj, newObj);
            cloneTo(origObj, newObj);
            return newObj;
        }

        <T> void cloneTo(T origObj, T newObj) throws InstantiationException {
            for (Field f : origObj.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()))
                    continue;
                long offset = UNSAFE.objectFieldOffset(f);
                if (f.getType() == boolean.class) {
                    UNSAFE.putBoolean(newObj, offset, UNSAFE.getBoolean(origObj, offset));
                } else if (f.getType() == byte.class) {
                    UNSAFE.putByte(newObj, offset, UNSAFE.getByte(origObj, offset));
                } else if (f.getType() == char.class) {
                    UNSAFE.putChar(newObj, offset, UNSAFE.getChar(origObj, offset));
                } else if (f.getType() == int.class) {
                    UNSAFE.putInt(newObj, offset, UNSAFE.getInt(origObj, offset));
                } else if (f.getType() == float.class) {
                    UNSAFE.putFloat(newObj, offset, UNSAFE.getFloat(origObj, offset));
                } else if (f.getType() == double.class) {
                    UNSAFE.putDouble(newObj, offset, UNSAFE.getDouble(origObj, offset));
                } else if (f.getType() == long.class) {
                    UNSAFE.putLong(newObj, offset, UNSAFE.getLong(origObj, offset));
                } else {
                    Object oldField = UNSAFE.getObject(origObj, offset);
                    if (oldField == null)
                        continue;
                    Object newField = map.get(oldField);
                    if (newField == null) {
                        Class<?> fClass = oldField.getClass();
                        if (fClass.isArray()) {
                            if (fClass == boolean[].class) {
                                boolean[] oldArr = (boolean[]) oldField;
                                boolean[] newArr = Arrays.copyOf(oldArr, oldArr.length);
                                newField = newArr;
                                map.put(oldField, newArr);
                            } else if (fClass == byte[].class) {
                                byte[] oldArr = (byte[]) oldField;
                                byte[] newArr = Arrays.copyOf(oldArr, oldArr.length);
                                newField = newArr;
                                map.put(oldField, newArr);
                            } else if (fClass == char[].class) {
                                char[] oldArr = (char[]) oldField;
                                char[] newArr = Arrays.copyOf(oldArr, oldArr.length);
                                newField = newArr;
                                map.put(oldField, newArr);
                            } else if (fClass == int[].class) {
                                int[] oldArr = (int[]) oldField;
                                int[] newArr = Arrays.copyOf(oldArr, oldArr.length);
                                newField = newArr;
                                map.put(oldField, newArr);
                            } else if (fClass == float[].class) {
                                float[] oldArr = (float[]) oldField;
                                float[] newArr = Arrays.copyOf(oldArr, oldArr.length);
                                newField = newArr;
                                map.put(oldField, newArr);
                            } else if (fClass == double[].class) {
                                double[] oldArr = (double[]) oldField;
                                double[] newArr = Arrays.copyOf(oldArr, oldArr.length);
                                newField = newArr;
                                map.put(oldField, newArr);
                            } else if (fClass == long[].class) {
                                long[] oldArr = (long[]) oldField;
                                long[] newArr = Arrays.copyOf(oldArr, oldArr.length);
                                newField = newArr;
                                map.put(oldField, newArr);
                            } else {
                                Object[] oldArr = (Object[]) oldField;
                                Object[] newArr = new Object[oldArr.length];
                                for (int i = 0; i < oldArr.length; i++) {
                                    Object oldItem = oldArr[i];
                                    if (oldArr[i] == null)
                                        continue;
                                    Object newItem = map.get(oldItem);
                                    if (newItem == null) {
                                        newItem = UNSAFE.allocateInstance(oldItem.getClass());
                                        map.put(oldItem, newItem);
                                        cloneTo(oldItem, newItem);
                                    }
                                    newArr[i] = newItem;
                                }
                                newField = newArr;
                            }
                        } else {
                            newField = UNSAFE.allocateInstance(fClass);
                            map.put(oldField, newField);
                            cloneTo(oldField, newField);
                        }
                    }
                    UNSAFE.putObject(newObj, offset, newField);
                }
            }
        }
    }
}
