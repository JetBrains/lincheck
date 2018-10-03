/**
 * Copyright 2013, Landz and its contributors. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package z.util;

/*
 * #%L
 * libtest
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

import sun.misc.Unsafe;
import z.annotation.NotThreadSafe;

import java.lang.reflect.Field;

import static z.util.Throwables.uncheckTo;

/**
 * All APIs to expose Unsafe side things in Hotspot for use.
 *
 * @Performance, we keep all exposed publics in constant as possible
 */
public class Unsafes {

  public static final int SIZE_BYTE_TYPE   = Byte.BYTES;
  public static final int SIZE_INT_TYPE    = Integer.BYTES;
  public static final int SIZE_SHORT_TYPE  = Short.BYTES;
  public static final int SIZE_CHAR_TYPE   = Character.BYTES;
  public static final int SIZE_LONG_TYPE   = Long.BYTES;
  public static final int SIZE_FLOAT_TYPE  = Float.BYTES;
  public static final int SIZE_DOUBLE_TYPE = Double.BYTES;


  public static final Unsafe UNSAFE = uncheckTo(() -> {
    Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
    theUnsafe.setAccessible(true);
    return (Unsafe) theUnsafe.get(null);
  });

  static final boolean is64bit = true; // auto detect if possible.public static void printAddresses(java.lang.String label, java.lang.Object... objects) {

  public static void printAddresses(String label, Object... objects) {
    System.out.print(label + ": 0x");
    long last = 0;
    int offset = UNSAFE.arrayBaseOffset(objects.getClass());
    int scale  = UNSAFE.arrayIndexScale(objects.getClass());
    switch (scale) {
      case 4:
        long factor = is64bit ? 8 : 1;
        final long i1 = (UNSAFE.getInt(objects, offset) & 0xFFFFFFFFL) * factor;
        System.out.print(Long.toHexString(i1));
        last = i1;
        for (int i = 1; i < objects.length; i++) {
          final long i2 = (UNSAFE.getInt(objects, offset + i * 4) & 0xFFFFFFFFL) * factor;
          if (i2 > last)
            System.out.print(", +" + Long.toHexString(i2 - last));
          else
            System.out.print(", -" + Long.toHexString(last - i2));
          last = i2;
        }
        break;
      case 8:
        throw new AssertionError("Not supported");
    }

    System.out.println();
  }

  /**the size (in bytes) of a native memory page. */
  public static final int SIZE_PAGE = UNSAFE.pageSize();

  public static final boolean isPageAligned(long address) {
    return (address & (SIZE_PAGE-1))==0;
  }

  /**
   * return the next page aligned address no matter the current address is
   * aligned or not. <p>
   * @param address
   * @return
   */
  public static final long nextPageAlignedAddress(long address) {
    return address-(address&(SIZE_PAGE-1))+SIZE_PAGE;
  }

  /**
   * return the next and nearest page aligned address. If the address has
   * already aligned, return input address itself.
   * <p>
   * see aslo {@link #nextPageAlignedAddress}
   * @param address
   * @return
   */
  public static final long nextNearestPageAlignedAddress(long address) {
    long masked = address&(SIZE_PAGE-1);
    if (masked!=0) {
      return address-masked+SIZE_PAGE;
    } else {
      return address;
    }
  }

  /** NOTE: SIZE_CACHE_LINE is CPU dependent, now for x86-64/x86. */
  public static final int SIZE_CACHE_LINE = 64;
  /** NOTE: Doubling for modern adjacent cache-line prefetch. */
  public static final int SIZE_CACHE_LINE_PADDING = 2*SIZE_CACHE_LINE;
  private static final int cacheLineSize() {
    return 64;
  }

  public static final boolean isCacheLineAligned(long address) {
    return (address&(SIZE_CACHE_LINE-1))==0;
  }

  public static final long nextCacheLineAlignedAddress(long address) {
    return address-(address&(SIZE_CACHE_LINE-1))+SIZE_CACHE_LINE;
  }

  public static final long nextNearestCacheLineAlignedAddress(long address) {
    long masked = address&(SIZE_CACHE_LINE-1);
    if (masked!=0) {
      return address-masked+SIZE_CACHE_LINE;
    } else {
      return address;
    }
  }

  /** The value of addressSize */
  public static final int SIZE_ADDRESS = UNSAFE.ADDRESS_SIZE;

  public static final long nextMachineWordAlignedAddress(long address) {
    return address-(address&(SIZE_ADDRESS-1))+SIZE_ADDRESS;
  }

  public static final long nextNearestMachineWordAlignedAddress(long address) {
    long masked = address&(SIZE_ADDRESS-1);
    if (masked!=0) {
      return address-masked+SIZE_ADDRESS;
    } else {
      return address;
    }
  }

  static long address(Object obj) {
    Object[] array = new Object[]{obj};
    long baseOffset = UNSAFE.arrayBaseOffset(Object[].class);
    return UNSAFE.getInt(array, baseOffset);
  }

  public static boolean isArchIntel64() {
    String arch = SystemProperty.OS_ARCH.value();
    return arch.equals("amd64") || arch.equals("x86_64");

  }

  public static boolean isArchIntel32() {
    String arch = SystemProperty.OS_ARCH.value();
    return arch.equals("i386") || arch.equals("x86");

  }

  /**
   * Note: here x86 arch includes x86 and x86-64
   * @return
   */
  public static boolean isArchX86() {
    String arch = SystemProperty.OS_ARCH.value();
    return arch.equals("i386") || arch.equals("x86")
        || arch.equals("amd64") || arch.equals("x86_64");
  }

  /**
   * a helper on {@link Unsafe#setMemory} to zero the offheap buffer.
   * @param addressOfBuffer
   * @param sizeOfBytes
   */
  public static void systemClearMemory(long addressOfBuffer, int sizeOfBytes) {
    switch (sizeOfBytes) {
      case 1 :
        UNSAFE.putByte(addressOfBuffer,(byte)0);
        break;
      case 2:
        UNSAFE.putShort(addressOfBuffer, (short) 0);
        break;
      case 4:
        UNSAFE.putInt(addressOfBuffer, 0);
        break;
      case 8:
        UNSAFE.putLong(addressOfBuffer, 0);
        break;
      case 16:
        UNSAFE.putLong(addressOfBuffer, 0);
        UNSAFE.putLong(addressOfBuffer+8,0);
        break;
      case 32:
        UNSAFE.putLong(addressOfBuffer,0);
        UNSAFE.putLong(addressOfBuffer+8,0);
        UNSAFE.putLong(addressOfBuffer+16,0);
        UNSAFE.putLong(addressOfBuffer+24,0);
        break;
      default:
        UNSAFE.setMemory(addressOfBuffer, sizeOfBytes, (byte)0);
    }
  }


  /**
   * Allocates a new block of native memory, of the given size in byte unit.
   * The contents of the memory are uninitialized; they will generally be
   * garbage.  The resulting native pointer will never be zero, and will be
   * aligned for all value types.
   *
   * @throws IllegalArgumentException if the size is negative or too large
   *         for the native size_t type
   *
   * @throws OutOfMemoryError if the allocation is refused by the system
   */
  public static long systemAllocateMemory(long sizeOfBytes) {
    return UNSAFE.allocateMemory(sizeOfBytes);
  }

  /**
   * Disposes of a block of native memory, as obtained from {@link
   * #systemAllocateMemory}.  The currentAddress passed to this method may be null, in which
   * case no action is taken.
   *
   * @see #systemAllocateMemory
   */
  public static void systemFreeMemory(long address) {
    UNSAFE.freeMemory(address);
  }

  //======================================================================
  // DSL for currentAddress

  /**
   * NOTE: <p>
   *   for support friendly DSL style, we still need OnAddress instance...
   *
   * XXX: is @NotThreadSafe OK?
   */
  @NotThreadSafe
  private static class OnAddressImpl implements OnAddress, OnAddressFollowBy {
    private OnAddressImpl() {}

    private long startAddress = 0L;
    private long currentAddress = 0L;

    private static final OnAddress startOn(long address) {
      OnAddressImpl instance  = new OnAddressImpl();
      instance.startAddress   = address;
      instance.currentAddress = address;
      return instance;
    }

    @Override
    public final OnAddressFollowBy put(int value){
      UNSAFE.putInt(currentAddress,value);
      currentAddress += 4;
      return this;
    }

    @Override
    public final OnAddressFollowBy put(long value){
      UNSAFE.putLong(currentAddress, value);
      currentAddress += 8;
      return this;
    }

    @Override
    public final OnAddressFollowBy put(byte value){
      UNSAFE.putByte(currentAddress,value);
      currentAddress += 1;
      return this;
    }

    @Override
    public final OnAddressFollowBy put(short value){
      UNSAFE.putShort(currentAddress, value);
      currentAddress += 2;
      return this;
    }

    @Override
    public final OnAddressFollowBy put(float value){
      UNSAFE.putFloat(currentAddress, value);
      currentAddress += 4;
      return this;
    }

    @Override
    public final OnAddressFollowBy put(double value){
      UNSAFE.putDouble(currentAddress, value);
      currentAddress += 8;
      return this;
    }

    @Override
    public final OnAddressFollowBy putAddress(long address){
      UNSAFE.putAddress(currentAddress,address);
      currentAddress += SIZE_ADDRESS;
      return this;
    }

    @Override
    public OnAddressFollowBy self() {
      return this;
    }

    @Override
    public final OnAddressFollowBy followBy(int value){
      put(value);
      return this;
    }

    @Override
    public OnAddressFollowBy followBy(long value) {
      put(value);
      return this;
    }

    @Override
    public OnAddressFollowBy followBy(byte value) {
      put(value);
      return this;
    }

    @Override
    public OnAddressFollowBy followBy(short value) {
      put(value);
      return this;
    }

    @Override
    public OnAddressFollowBy followBy(float value) {
      put(value);
      return this;
    }

    @Override
    public OnAddressFollowBy followBy(double value) {
      put(value);
      return this;
    }

    @Override
    public OnAddressFollowBy followByAddress(long address) {
      putAddress(address);
      return this;
    }

    @Override
    public OnAddressFollowBy paddedBy(long length, byte paddingValue) {
      UNSAFE.setMemory(currentAddress, length, paddingValue);
      currentAddress += length;
      return this;
    }

    @Override
    public OnAddressFollowBy paddedBy(long length) {
      paddedBy(length, (byte) 0);
      return this;
    }

    @Override
    public OnAddressFollowBy paddedToNextNearestMachineWordAlignedAddress() {
      long addr = nextNearestMachineWordAlignedAddress(currentAddress);
      long length = addr-currentAddress;
      if (length!=0)
        paddedBy(length);
      return this;
    }

    @Override
    public OnAddressFollowBy paddedToNextNearestCacheLineAlignedAddress() {
      long addr = nextNearestCacheLineAlignedAddress(currentAddress);
      long length = addr-currentAddress;
      if (length!=0)
        paddedBy(length);
      return this;
    }

    @Override
    public OnAddressFollowBy paddedToNextNearestPageAlignedAddress() {
      long addr = nextNearestPageAlignedAddress(currentAddress);
      long length = addr-currentAddress;
      if (length!=0)
        paddedBy(length);
      return this;
    }

    @Override
    public OnAddressFollowBy paddedToNextMachineWordAlignedAddress() {
      paddedBy(nextMachineWordAlignedAddress(currentAddress) -currentAddress);
      return this;
    }

    @Override
    public OnAddressFollowBy paddedToNextCacheLineAlignedAddress() {
      paddedBy(nextCacheLineAlignedAddress(currentAddress) -currentAddress);
      return this;
    }

    @Override
    public OnAddressFollowBy paddedToNextPageAlignedAddress() {
      paddedBy(nextPageAlignedAddress(currentAddress) -currentAddress);
      return this;
    }

    @Override
    public OnAddressFollowBy gotoNextNearestMachineWordAlignedAddress() {
      currentAddress = nextNearestMachineWordAlignedAddress(currentAddress);
      return this;
    }

    @Override
    public OnAddressFollowBy gotoNextNearestCacheLineAlignedAddress() {
      currentAddress = nextNearestCacheLineAlignedAddress(currentAddress);
      return this;
    }

    @Override
    public OnAddressFollowBy gotoNextNearestPageAlignedAddress() {
      currentAddress = nextNearestPageAlignedAddress(currentAddress);
      return this;
    }

    @Override
    public OnAddressFollowBy gotoNextMachineWordAlignedAddress() {
      currentAddress = nextMachineWordAlignedAddress(currentAddress);
      return this;
    }

    @Override
    public OnAddressFollowBy gotoNextCacheLineAlignedAddress() {
      currentAddress = nextCacheLineAlignedAddress(currentAddress);
      return this;
    }

    @Override
    public OnAddressFollowBy gotoNextPageAlignedAddress() {
      currentAddress = nextPageAlignedAddress(currentAddress);
      return this;
    }

    @Override
    public OnAddressFollowBy gotoAddress(long address) {
      currentAddress = address;
      return this;
    }

    @Override
    public long endAddress() {
      return currentAddress;
    }

    @Override
    public byte[] toByteArray() {
      //length>=1
      int length = (int)(currentAddress-startAddress);
      byte[] rt = new byte[length];
      long offset = UNSAFE.ARRAY_BYTE_BASE_OFFSET;
      UNSAFE.copyMemory(null,startAddress,rt,offset,length);
      return rt;
    }

    @Override
    public void toByteArray(byte[] dst) {
      //contract(()->dst.length>0);
      int lengthSrc = (int)(currentAddress-startAddress+1);
      int lengthDst = dst.length;
      int minLength = lengthSrc<lengthDst ? lengthDst : dst.length;
      long offset = UNSAFE.ARRAY_BYTE_BASE_OFFSET;
      UNSAFE.copyMemory(null,startAddress,dst,offset,minLength);
    }

  }

  @NotThreadSafe
  public static interface OnAddress {
    public OnAddressFollowBy put(int value);
    public OnAddressFollowBy put(long value);
    public OnAddressFollowBy put(byte value);
    public OnAddressFollowBy put(short value);
    public OnAddressFollowBy put(float value);
    public OnAddressFollowBy put(double value);

    /**
     * <p>
     * NOTE: <p>
     *   1. currentAddress is unsigned int <p>
     *   2. currentAddress size depends on the system  <p>
     *   3. use currentAddress as currentAddress for all usages  <p>
     *      (Don't mess up with long unless you know what you are doing)  <p>
     */
    public OnAddressFollowBy putAddress(long address);

    /**
     * return the instance itself as a convenient method to
     * start padding directly.
     */
    public OnAddressFollowBy self();
  }


  public static interface OnAddressFollowBy {
    public OnAddressFollowBy followBy(int value);
    public OnAddressFollowBy followBy(long value);
    public OnAddressFollowBy followBy(byte value);
    public OnAddressFollowBy followBy(short value);
    public OnAddressFollowBy followBy(float value);
    public OnAddressFollowBy followBy(double value);
    public OnAddressFollowBy followByAddress(long address);

    /**
     * the current address will be padded wtih some paddingValue in some length.
     * <p>
     */
    public OnAddressFollowBy paddedBy(long length, byte paddingValue);

    /**
     * same to call with paddedBy(length, 0)
     * <p>
     */
    public OnAddressFollowBy paddedBy(long length);

    public OnAddressFollowBy paddedToNextNearestMachineWordAlignedAddress();
    public OnAddressFollowBy paddedToNextNearestCacheLineAlignedAddress();
    public OnAddressFollowBy paddedToNextNearestPageAlignedAddress();

    public OnAddressFollowBy paddedToNextMachineWordAlignedAddress();
    public OnAddressFollowBy paddedToNextCacheLineAlignedAddress();
    public OnAddressFollowBy paddedToNextPageAlignedAddress();

    public OnAddressFollowBy gotoNextNearestMachineWordAlignedAddress();
    public OnAddressFollowBy gotoNextNearestCacheLineAlignedAddress();
    public OnAddressFollowBy gotoNextNearestPageAlignedAddress();

    public OnAddressFollowBy gotoNextMachineWordAlignedAddress();
    public OnAddressFollowBy gotoNextCacheLineAlignedAddress();
    public OnAddressFollowBy gotoNextPageAlignedAddress();

    public OnAddressFollowBy gotoAddress(long address);

    public long endAddress();

    /**
     * XXX: we may need an end() to seal the whole DSL?
     * <p>
     * @return dst - a new byte array contains the content from starting  to
     *               current address.
     */
    public byte[] toByteArray();

    /**
     * variant of {@link #toByteArray()}
     * contract: dst.length>0
     * <p>
     * the content of dst will be dst.length bytes of the content from starting
     * to current address. The remaining content of dst after outputting is
     * untouchded.
     *
     */
    public void toByteArray(byte[] dst);
  }


  public static final OnAddress onAddress(long address) {
    return OnAddressImpl.startOn(address);
  }


  /**
   * Returns the thread id for the given thread.  We must access
   * this directly rather than via method Thread.getId() because
   * getId() is not final, and has been known to be overridden in
   * ways that do not preserve unique mappings.
   */
  private static final long TID_OFFSET = uncheckTo(() -> {
    return UNSAFE.objectFieldOffset(Thread.class.getDeclaredField("tid"));
  });

  /**
   * we don't use volatile version, based on the following reasons/assumptions:
   *   1. init() called by Thread constructor is before start();
   *   2. "A call to start() on a thread happens-before any actions in the
   *      started thread";
   *   3. there is no tid modifier (in source code form) except Thread itself
   *      (so "effective final");
   *
   * @param thread
   * @return
   */
  public static final long threadId(Thread thread) {
    return UNSAFE.getLong(thread, TID_OFFSET);
  }

  public static final long currentThreadId() {
    return UNSAFE.getLong(Thread.currentThread(), TID_OFFSET);
  }


}