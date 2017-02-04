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

package z.offheap.buffer;

/*
 * #%L
 * libtest
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

import java.nio.ByteOrder;

import static z.util.Unsafes.UNSAFE;

/**
 */
public final class Buffers {

  public static final boolean NATIVE_ORDER_SAME_TO_NETWORK_ORDER =
      (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);

  //=====================================================================
  //primitives get helpers

  public static final byte get(long address) {
    return UNSAFE.getByte(address);
  }

  public static final int getInt(long address) {
    return UNSAFE.getInt(address);
  }

  public static final int getIntNonNative(long address) {
    return Integer.reverseBytes(UNSAFE.getInt(address));
  }

  public static final long getLong(long address) {
    return UNSAFE.getLong(address);
  }

  public static final long getLongNonNative(long address) {
    return Long.reverseBytes(UNSAFE.getLong(address));
  }

  public static final short getShort(long address){
    return UNSAFE.getShort(address);
  }

  public static final short getShortNonNative(long address){
    return Short.reverseBytes(UNSAFE.getShort(address));
  }

  public static final float getFloat(long address) {
    return UNSAFE.getFloat(address);
  }

  public static final float getFloatNonNative(long address) {
    return Float.intBitsToFloat(getIntNonNative(address));
  }

  public static final double getDouble(long address) {
    return UNSAFE.getDouble(address);
  }

  public static final double getDoubleNonNative(long address) {
    return Double.longBitsToDouble(getLongNonNative(address));
  }

  public static final long getAddress(long address) {
    return UNSAFE.getAddress(address);
  }

  //=====================================================================
  //primitives put helpers

  public static final void put(long address, byte value) {
    UNSAFE.putByte(address,value);
  }

  public static final void putInt(long address, int value) {
    UNSAFE.putInt(address,value);
  }

  public static final void putIntNonNative(long address, int value) {
    UNSAFE.putInt(address, Integer.reverseBytes(value));
  }

  public static final void putLong(long address, long value) {
    UNSAFE.putLong(address, value);
  }

  public static final void putLongNonNative(long address, long value) {
    UNSAFE.putLong(address, Long.reverseBytes(value));
  }

  public static final void putShort(long address, short value) {
    UNSAFE.putShort(address, value);
  }

  public static final void putShortNonNative(long address, short value) {
    UNSAFE.putShort(address, Short.reverseBytes(value));
  }

  public static final void putFloat(long address, float value) {
    UNSAFE.putFloat(address, value);
  }

  public static final void putFloatNonNative(long address, float value) {
    putIntNonNative(address, Float.floatToRawIntBits(value));
  }

  public static final void putDouble(long address, double value) {
    UNSAFE.putDouble(address, value);
  }

  public static final void putDoubleNonNative(long address, double value) {
    putLongNonNative(address, Double.doubleToRawLongBits(value));
  }

  public static final void putAddress(long address,long addrValue) {
    UNSAFE.putAddress(address,addrValue);
  }

  //=====================================================================
  //unsign primitive helpers

  public static final int toUnsignedByte(byte value) {
    return value & 0xFF;
  }

  public static final int toUnsignedShort(short value) {
    return value & 0xFFFF;
  }

  public static final long toUnsignedInt(int value) {
    return value & 0xFFFFFFFFL;
  }

  //=====================================================================
  //boolean primitive helpers

  public static final boolean toBoolean(byte value) {
    return value==0 ? false:true;
  }

  public static final boolean toBoolean(int value) {
    return value==0 ? false:true;
  }

  public static final boolean toBoolean(long value) {
    return value==0 ? false:true;
  }

  public static final byte byteFromBoolean(boolean b) {
    return b==false ? 0: (byte)1;
  }

  public static final int intFromBoolean(boolean b) {
    return b==false ? 0: 1;
  }

  public static final long longFromBoolean(boolean b) {
    return b==false ? 0: 1L;
  }

  //=====================================================================
  //system helpers

  //wow....
  public static final void clear(long address, int length) {
//    contract(() -> length > 0);

    switch (length) {
      case 1 :
        UNSAFE.putByte(address, (byte)0);
        break;
      case 2:
        UNSAFE.putShort(address, (short)0);
        break;
      case 3:
        UNSAFE.putByte(address, (byte)0);
        UNSAFE.putShort(address+1, (short)0);
        break;
      case 4:
        UNSAFE.putInt(address, 0);
        break;
      case 5:
        UNSAFE.putByte(address, (byte)0);
        UNSAFE.putInt(address+1, 0);
        break;
      case 6:
        UNSAFE.putShort(address, (short) 0);
        UNSAFE.putInt(address+2, 0);
        break;
      case 7:
        UNSAFE.putByte(address, (byte)0);
        UNSAFE.putShort(address+1, (short)0);
        UNSAFE.putInt(address+3, 0);
        break;
      case 8:
        UNSAFE.putLong(address, 0);
        break;
      case 9:
        UNSAFE.putByte(address, (byte)0);
        UNSAFE.putLong(address+1, 0);
        break;
      case 10:
        UNSAFE.putShort(address, (short)0);
        UNSAFE.putLong(address+2, 0);
        break;
      case 11:
        UNSAFE.putByte(address, (byte) 0);
        UNSAFE.putShort(address+1, (short)0);
        UNSAFE.putLong(address+3, 0);
        break;
      case 12:
        UNSAFE.putInt(address, 0);
        UNSAFE.putLong(address+4, 0);
        break;
      case 13:
        UNSAFE.putByte(address, (byte) 0);
        UNSAFE.putInt(address+1, 0);
        UNSAFE.putLong(address+5, 0);
        break;
      case 14:
        UNSAFE.putShort(address, (short) 0);
        UNSAFE.putInt(address+2, 0);
        UNSAFE.putLong(address+6, 0);
        break;
      case 15:
        UNSAFE.putByte(address, (byte) 0);
        UNSAFE.putShort(address+1, (short) 0);
        UNSAFE.putInt(address+3, 0);
        UNSAFE.putLong(address+7, 0);
        break;
      case 16:
        UNSAFE.putLong(address, 0);
        UNSAFE.putLong(address+8,0);
        break;
      case 17:
        UNSAFE.putByte(address, (byte)0);
        UNSAFE.putLong(address+1, 0);
        UNSAFE.putLong(address+9, 0);
        break;
      case 18:
        UNSAFE.putShort(address, (short)0);
        UNSAFE.putLong(address+2, 0);
        UNSAFE.putLong(address+10,0);
        break;
      case 19:
        UNSAFE.putByte(address, (byte)0);
        UNSAFE.putShort(address+1, (short)0);
        UNSAFE.putLong(address+3, 0);
        UNSAFE.putLong(address+11,0);
        break;
      case 32:
        UNSAFE.putLong(address,0);
        UNSAFE.putLong(address+8,0);
        UNSAFE.putLong(address+16,0);
        UNSAFE.putLong(address+24,0);
        break;
      default:
        UNSAFE.setMemory(address, length, (byte)0);
    }
  }

  public static final void clearLong(long address, long length) {
    UNSAFE.setMemory(address, length, (byte)0);
  }

  public static final void fill(long address, int length, byte value) {
//    contract(() -> length > 0);

    switch (length) {
      case 1 :
        UNSAFE.putByte(address, value);
        break;
      case 2:
        UNSAFE.putByte(address, value);
        UNSAFE.putByte(address+1, value);
        break;
      case 3:
        UNSAFE.putByte(address, value);
        UNSAFE.putByte(address+1, value);
        UNSAFE.putByte(address+2, value);
        break;
      case 4:
        UNSAFE.putByte(address, value);
        UNSAFE.putByte(address+1, value);
        UNSAFE.putByte(address+2, value);
        UNSAFE.putByte(address+3, value);
        break;
      default:
        UNSAFE.setMemory(address, length, value);
    }

  }

  public static final void copy(
      long srcStartAddress, long dstStartAddress, long length) {
    UNSAFE.copyMemory(srcStartAddress,dstStartAddress,length);
  }


}
