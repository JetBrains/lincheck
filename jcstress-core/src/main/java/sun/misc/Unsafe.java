/*
 * Copyright (c) 2005, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package sun.misc;

import java.lang.reflect.Field;

/**
 * Transitional interface, allows to compile the project against old JDKs.
 */
public abstract class Unsafe {

    public abstract void loadFence();

    public abstract void storeFence();

    public abstract void fullFence();

    public abstract Object getObject(Object o, long offset);

    public abstract void putObject(Object o, long offset, Object x);

    public abstract boolean getBoolean(Object o, long offset);

    public abstract void putBoolean(Object o, long offset, boolean x);

    public abstract short getShort(Object o, long offset);

    public abstract void putShort(Object o, long offset, short x);

    public abstract char getChar(Object o, long offset);

    public abstract void putChar(Object o, long offset, char x);

    public abstract float getFloat(Object o, long offset);

    public abstract void putFloat(Object o, long offset, float x);

    public abstract double getDouble(Object o, long offset);

    public abstract void putDouble(Object o, long offset, double x);

    public abstract int getInt(Object o, int offset);

    public abstract void putInt(Object o, int offset, int x);

    public abstract Object getObject(Object o, int offset);

    public abstract void putObject(Object o, int offset, Object x);

    public abstract boolean getBoolean(Object o, int offset);

    public abstract void putBoolean(Object o, int offset, boolean x);

    public abstract byte getByte(Object o, int offset);

    public abstract void putByte(Object o, int offset, byte x);

    public abstract short getShort(Object o, int offset);

    public abstract void putShort(Object o, int offset, short x);

    public abstract char getChar(Object o, int offset);

    public abstract void putChar(Object o, int offset, char x);

    public abstract void putLong(Object o, int offset, long x);

    public abstract float getFloat(Object o, int offset);

    public abstract void putFloat(Object o, int offset, float x);

    public abstract byte getByte(long address);

    public abstract void putByte(long address, byte x);

    public abstract short getShort(long address);

    public abstract void putShort(long address, short x);

    public abstract char getChar(long address);

    public abstract void putChar(long address, char x);

    public abstract int getInt(long address);

    public abstract void putInt(long address, int x);

    public abstract long getLong(long address);

    public abstract void putLong(long address, long x);

    public abstract float getFloat(long address);

    public abstract void putFloat(long address, float x);

    public abstract double getDouble(long address);

    public abstract void putDouble(long address, double x);

    public abstract long getAddress(long address);

    public abstract void putAddress(long address, long x);

    public abstract long allocateMemory(long bytes);

    public abstract long reallocateMemory(long address, long bytes);

    public abstract void setMemory(Object o, long offset, long bytes, byte value);

    public abstract void setMemory(long address, long bytes, byte value);

    public abstract void copyMemory(Object srcBase, long srcOffset,
                                  Object destBase, long destOffset,
                                  long bytes);

    public abstract void copyMemory(long srcAddress, long destAddress, long bytes);

    public abstract void freeMemory(long address);

    public abstract Object staticFieldBase(Class<?> c);

    public abstract long staticFieldOffset(Field f);

    public abstract long objectFieldOffset(Field f);

    public abstract Object staticFieldBase(Field f);

    public abstract boolean shouldBeInitialized(Class<?> c);

    public abstract void ensureClassInitialized(Class<?> c);

    public abstract int addressSize();

    public abstract int pageSize();

    public abstract Class<?> defineClass(String name, byte[] b, int off, int len);

    public abstract Class<?> defineAnonymousClass(Class<?> hostClass, byte[] data, Object[] cpPatches);

    public abstract Object allocateInstance(Class<?> cls)
            throws InstantiationException;

    public abstract void monitorEnter(Object o);

    public abstract void monitorExit(Object o);

    public abstract boolean tryMonitorEnter(Object o);

    public abstract void throwException(Throwable ee);

    public abstract boolean compareAndSwapObject(Object o, long offset,
                                                     Object expected,
                                                     Object x);

    public abstract boolean compareAndSwapInt(Object o, long offset,
                                                  int expected,
                                                  int x);

    public abstract boolean compareAndSwapLong(Object o, long offset,
                                                   long expected,
                                                   long x);

    public abstract Object getObjectVolatile(Object o, long offset);

    public abstract void putObjectVolatile(Object o, long offset, Object x);

    public abstract int getIntVolatile(Object o, long offset);

    public abstract void putIntVolatile(Object o, long offset, int x);

    public abstract boolean getBooleanVolatile(Object o, long offset);

    public abstract void putBooleanVolatile(Object o, long offset, boolean x);

    public abstract byte getByteVolatile(Object o, long offset);

    public abstract void putByteVolatile(Object o, long offset, byte x);

    public abstract short getShortVolatile(Object o, long offset);

    public abstract void putShortVolatile(Object o, long offset, short x);

    public abstract char getCharVolatile(Object o, long offset);

    public abstract void putCharVolatile(Object o, long offset, char x);

    public abstract long getLongVolatile(Object o, long offset);

    public abstract void putLongVolatile(Object o, long offset, long x);

    public abstract float getFloatVolatile(Object o, long offset);

    public abstract void putFloatVolatile(Object o, long offset, float x);

    public abstract double getDoubleVolatile(Object o, long offset);

    public abstract void putDoubleVolatile(Object o, long offset, double x);

    public abstract void putOrderedObject(Object o, long offset, Object x);

    public abstract void putOrderedInt(Object o, long offset, int x);

    public abstract void putOrderedLong(Object o, long offset, long x);

    public abstract void unpark(Object thread);

    public abstract void park(boolean isAbsolute, long time);

    public abstract int getLoadAverage(double[] loadavg, int nelems);

    public static Unsafe getUnsafe() {
        throw new IllegalStateException("Can't touch this");
    }

    public abstract long getLong(java.lang.Object o, long l);

    public abstract void putLong(java.lang.Object o, long l, long l1);

    public abstract int getInt(java.lang.Object o, long l);

    public abstract void putInt(java.lang.Object o, long l, int l1);

    public abstract int getByte(java.lang.Object o, long l);

    public abstract void putByte(java.lang.Object o, long l, byte l1);

    public abstract int arrayBaseOffset(java.lang.Class<?> aClass);

    public abstract int arrayIndexScale(java.lang.Class<?> aClass);

    public abstract int getAndAddInt(Object o, long offset, int delta);

    public abstract long getAndAddLong(Object o, long offset, long delta);

    public abstract int getAndSetInt(Object o, long offset, int x);

    public abstract long getAndSetLong(Object o, long offset, long x);

    public abstract Object getAndSetObject(Object o, long offset, Object x);

}
