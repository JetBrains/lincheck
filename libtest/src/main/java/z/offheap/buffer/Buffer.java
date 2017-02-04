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

import static z.offheap.buffer.Buffers.*;
import static z.offheap.zmalloc.Allocator.allocate;
import static z.offheap.zmalloc.Allocator.free;
import static z.util.Contracts.contract;

/**
* Buffer, is one kind off-heap memory storage based on
* {@link z.offheap.zmalloc.Allocator}.
* <p>
* Note:
 * <p>1. the Buffer instance is not thread safe.
 * <p>2. the buffer storage may contain garbage when created. It is your judgement
 *    whether to do this clear work via {@link #clear} method.
 *
*/

public class Buffer implements
    NativeOrderBuffer, NetworkOrderBuffer, LittleEndianOrderBuffer {

  protected long capacity;
  protected long address;
  protected long readCursor;
  protected long writeCursor;


  /**
   * WARNING: this constructor is used to support the pre-allocated behavior in
   *          pool-like scenario. This default constructor just returns an
   *          uncompleted instance.
   *<p>
   *          Do not use this constructor unless you know what you are doing.
   *          Instead, you can use related static factory methods,
   *          like {@link #create}.
   */
  @Deprecated
  public Buffer() {}

  protected Buffer(long capacity) {
    this.address  = allocate(capacity);
    this.capacity = capacity;
  }

  protected Buffer(long address, long capacity) {
    this.address  = address;
    this.capacity = capacity;
  }

  public static final ByteBuffer create(long capacity) {
    return new Buffer(capacity);
  }

  public static final ByteBuffer create(long address, long capacity) {
    return new Buffer(address, capacity);
  }

  @Override
  public long capacity() {
    return capacity;
  }

  @Deprecated
  public void capacity(long capacity) {
    this.capacity = capacity;
  }

  @Override
  public long address() {
    return address;
  }

  @Deprecated
  public void address(long address) {
    this.address = address;
  }

  @Override
  public final Buffer clear() {
    Buffers.clear(address, (int) capacity);
    return this;
  }

  @Override
  public final long readCursor() {
    return readCursor;
  }

  @Override
  public final long writeCursor() {
    return writeCursor;
  }

  @Override
  public final Buffer reset() {
    readCursor = 0;
    writeCursor = 0;
    return this;
  }

  @Override
  public final boolean isReadable() {
    return writeCursor > readCursor;
  }

  @Override
  public final boolean isReadable(long numBytes) {
    return writeCursor - readCursor >= numBytes;
  }

  @Override
  public final boolean isWritable() {
    return capacity > writeCursor;
  }

  @Override
  public boolean isWritable(long numBytes) {
    return capacity - writeCursor >= numBytes;
  }

  @Override
  public final long readableBytes() {
    return writeCursor - readCursor;
  }

  @Override
  public final long writableBytes() {
    return capacity - writeCursor;
  }


  //=======================================================================
  //read primitives

  @Override
  public final byte read() {
    contractToRead(1);
    byte v = get(address + readCursor);
    readCursor++;
    return v;
  }

  @Override
  public final short readShort() {
    contractToRead(2);
    short v = getShort(address + readCursor);
    readCursor += 2;
    return v;
  }

  @Override
  public final int readInt() {
    contractToRead(4);
    int v = getInt(address + readCursor);
    readCursor += 4;
    return v;
  }

  @Override
  public final long readLong() {
    contractToRead(8);
    long v = getLong(address + readCursor);
    readCursor += 8;
    return v;
  }

  @Override
  public final char readChar() {
    return (char)readShort();
  }

  @Override
  public final float readFloat() {
    contractToRead(4);
    float v = getFloat(address + readCursor);
    readCursor += 4;
    return v;
  }

  @Override
  public final double readDouble() {
    contractToRead(8);
    double v = getDouble(address + readCursor);
    readCursor += 8;
    return v;
  }

  //======================================================================
  //NetworkOrder read
  @Override
  public final short readShortN() {
    contractToRead(2);
    short v = NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        getShort(address + readCursor) : getShortNonNative(address + readCursor);
    readCursor += 2;
    return v;
  }

  @Override
  public final int readIntN() {
    contractToRead(4);
    int v = NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        getInt(address + readCursor) : getIntNonNative(address + readCursor);
    readCursor += 4;
    return v;
  }

  @Override
  public final long readLongN() {
    contractToRead(8);
    long v = NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        getLong(address + readCursor) : getLongNonNative(address + readCursor);
    readCursor += 8;
    return v;
  }

  @Override
  public final char readCharN() {
    return NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        (char)readShort() : (char) readShortN();
  }

  @Override
  public final float readFloatN() {
    contractToRead(4);
    float v = NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        getFloat(address+readCursor) : getFloatNonNative(address+readCursor);
    readCursor += 4;
    return v;
  }

  @Override
  public final double readDoubleN() {
    contractToRead(8);
    double v = NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        getDouble(address+readCursor) : getDoubleNonNative(address+readCursor);
    readCursor += 8;
    return v;
  }

  //======================================================================
  //LittleEndian read
  @Override
  public final short readShortLE() {
    contractToRead(2);
    short v = NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
         getShortNonNative(address+readCursor) : getShort(address+readCursor);
    readCursor += 2;
    return v;
  }

  @Override
  public final int readIntLE() {
    contractToRead(4);
    int v = NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        getIntNonNative(address + readCursor) : getInt(address + readCursor) ;
    readCursor += 4;
    return v;
  }

  @Override
  public final long readLongLE() {
    contractToRead(8);
    long v = NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        getLongNonNative(address + readCursor) : getLong(address + readCursor);
    readCursor += 8;
    return v;
  }

  @Override
  public final char readCharLE() {
    return NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        (char)readShortLE() : (char)readShort();
  }

  @Override
  public final float readFloatLE() {
    contractToRead(4);
    float v = NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        getFloatNonNative(address+readCursor) : getFloat(address+readCursor);
    readCursor += 4;
    return v;
  }

  @Override
  public final double readDoubleLE() {
    contractToRead(8);
    double v = NATIVE_ORDER_SAME_TO_NETWORK_ORDER ?
        getDoubleNonNative(address+readCursor) : getDouble(address+readCursor);
    readCursor += 8;
    return v;
  }

  /**
   *
   * WARNING: make sure you understand the bytes that you want to skip may
   *          contain garbage, in that {@link z.offheap.buffer.Buffer} does not
   *          clear the allocated memory area when created.
   */
  @Override
  public final void skipRead(long length) {
    contractReadCursor(readCursor+length);
    readCursor += length;
  }

  /**
   *
   * WARNING: make sure you understand the bytes that you want to skip may
   *          contain garbage, in that {@link z.offheap.buffer.Buffer} does not
   *          clear the allocated memory area when created.
   */
  @Override
  public final void skipReadTo(long index) {
    contractReadCursor(index);
    readCursor = index;
  }

  @Override
  public final void readTo(ByteBuffer dstbuffer, long length) {
    copy(address+readCursor,
        dstbuffer.address()+dstbuffer.writeCursor(),
        length);
    dstbuffer.skipWrite(length);
  }

  //======================================================================
  //write primitives

  @Override
  public final Buffer write(byte value) {
    contractToWrite(1);
    put(address + writeCursor, value);
    writeCursor++;
    return this;
  }

  @Override
  public final Buffer writeShort(short value) {
    contractToWrite(2);
    putShort(address + writeCursor, value);
    writeCursor += 2;
    return this;
  }

  @Override
  public final Buffer writeInt(int value) {
    contractToWrite(4);
    putInt(address + writeCursor, value);
    writeCursor += 4;
    return this;
  }

  @Override
  public final Buffer writeLong(long value) {
    contractToWrite(8);
    putLong(address + writeCursor, value);
    writeCursor += 8;
    return this;
  }

  @Override
  public final Buffer writeChar(char value) {
    writeShort((short) value);
    return this;
  }

  @Override
  public final Buffer writeFloat(float value) {
    contractToWrite(4);
    putFloat(address + writeCursor, value);
    writeCursor += 4;
    return this;
  }

  @Override
  public final Buffer writeDouble(double value) {
    contractToWrite(8);
    putDouble(address + writeCursor, value);
    writeCursor += 8;
    return this;
  }

  //=======================================================================
  //NetworkOrder write
  @Override
  public final Buffer writeShortN(short value) {
    contractToWrite(2);
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      putShort(address + writeCursor, value);
    }else {
      putShortNonNative(address + writeCursor, value);
    }
    writeCursor += 2;
    return this;
  }

  @Override
  public final Buffer writeIntN(int value) {
    contractToWrite(4);
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      putInt(address + writeCursor, value);
    } else {
      putIntNonNative(address + writeCursor, value);
    }
    writeCursor += 4;
    return this;
  }

  @Override
  public final Buffer writeLongN(long value) {
    contractToWrite(8);
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      putLong(address + writeCursor, value);
    } else {
      putLongNonNative(address + writeCursor, value);
    }
    writeCursor += 8;
    return this;
  }

  @Override
  public final Buffer writeCharN(char value) {
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      writeShort((short) value);
    } else {
      writeShortN((short) value);
    }
    return this;
  }

  @Override
  public final Buffer writeFloatN(float value) {
    contractToWrite(4);
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      putFloat(address + writeCursor, value);
    } else {
      putFloatNonNative(address + writeCursor, value);
    }
    writeCursor += 4;
    return this;
  }

  @Override
  public final Buffer writeDoubleN(double value) {
    contractToWrite(8);
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      putDouble(address + writeCursor, value);
    } else {
      putDoubleNonNative(address + writeCursor, value);
    }
    writeCursor += 8;
    return this;
  }

  //=======================================================================
  //LittleEndianOrder write
  @Override
  public final Buffer writeShortLE(short value) {
    contractToWrite(2);
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      putShortNonNative(address + writeCursor, value);
    }else {
      putShort(address + writeCursor, value);
    }
    writeCursor += 2;
    return this;
  }

  @Override
  public final Buffer writeIntLE(int value) {
    contractToWrite(4);
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      putIntNonNative(address + writeCursor, value);
    } else {
      putInt(address + writeCursor, value);
    }
    writeCursor += 4;
    return this;
  }

  @Override
  public final Buffer writeLongLE(long value) {
    contractToWrite(8);
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      putLongNonNative(address + writeCursor, value);
    } else {
      putLong(address + writeCursor, value);
    }
    writeCursor += 8;
    return this;
  }

  @Override
  public final Buffer writeCharLE(char value) {
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      writeShortLE((short) value);
    } else {
      writeShort((short) value);
    }
    return this;
  }

  @Override
  public final Buffer writeFloatLE(float value) {
    contractToWrite(4);
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      putFloatNonNative(address + writeCursor, value);
    } else {
      putFloat(address + writeCursor, value);
    }
    writeCursor += 4;
    return this;
  }

  @Override
  public final Buffer writeDoubleLE(double value) {
    contractToWrite(8);
    if (NATIVE_ORDER_SAME_TO_NETWORK_ORDER) {
      putDoubleNonNative(address + writeCursor, value);
    } else {
      putDouble(address + writeCursor, value);
    }
    writeCursor += 8;
    return this;
  }

  /**
   *
   * WARNING: make sure you understand the bytes that you want to skip may
   *          contain garbage, in that {@link z.offheap.buffer.Buffer} does not
   *          clear the allocated memory area when created.
   */
  @Override
  @Deprecated
  public final Buffer skipWrite(long length) {
    contractWriteCursor(writeCursor+length);
    writeCursor += length;
    return this;
  }

  /**
   *
   * WARNING: make sure you understand the bytes that you want to skip may
   *          contain garbage, in that {@link z.offheap.buffer.Buffer} does not
   *          clear the allocated memory area when created.
   */
  @Override
  @Deprecated
  public final Buffer skipWriteTo(long index) {
    contractWriteCursor(index);
    writeCursor = index;
    return this;
  }


  //======================================================================
  //common contracts
  protected static boolean enable_contracts = true;

  protected final void contractToWrite(long nBytes) {
    if (enable_contracts)
      contract(()-> (address!=0) && isWritable(nBytes) );
  }

  protected final void contractToRead(long nBytes) {
    if (enable_contracts)
    contract(()-> (address!=0) && isReadable(nBytes) );
  }

  protected final void contractReadCursor(long readCursor) {
    if (enable_contracts)
    contract(()->
        (address!=0) && (readCursor > 0) && (readCursor < writeCursor) );
  }

  protected final void contractWriteCursor(long writeCursor) {
    if (enable_contracts)
    contract(()->
        (address!=0) && (writeCursor > 0) && (writeCursor < capacity) );
  }

  public static final void disableContracts() {
    enable_contracts = false;
  }


  //======================================================================
  @Override
  public final NativeOrderBuffer nativeOrder() {
    return this;
  }

  @Override
  public final NetworkOrderBuffer networkOrder() {
    return this;
  }

  @Override
  public final LittleEndianOrderBuffer littleEndianOrder() {
    return this;
  }

  //======================================================================
  //clean-up

  @Override
  public final void close() {
    if (address!=0) {
      free(address);
      address=0;
    }
  }

  @Override
  public final void finalize() {
    close();
  }

}

