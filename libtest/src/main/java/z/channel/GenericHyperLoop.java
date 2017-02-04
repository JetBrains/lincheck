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

package z.channel;

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

import static z.util.Contracts.contract;
import static z.util.Unsafes.*;

/**
 * Generic variant of {@link z.channel.LongHyperLoop}
 */
public final class GenericHyperLoop<T> implements BroadcastChannel<T> {
  private static final int BASE  = UNSAFE.ARRAY_OBJECT_BASE_OFFSET;
  private static final int SCALE = UNSAFE.ARRAY_OBJECT_INDEX_SCALE;

  //TODO: add a config option for SIZE_HYPERLOOP_BASE?
  /**
   * NOTE: you should reserve 128*(4+number of consumers of LongHyperLoop),
   * otherwise you may crash your JVM.
   * <p>
   * contract:
   * 1. now keep it a multiple of 4096
   */
  private static final int SIZE_HYPERLOOP_BASE = 4096;//only for 28 consumers?

  private final long addressRaw;
  private final long addressHyperLoop;

  private final int nBufferSlots;
  private final int bufferSlotMask;

  private final long addrWriteCursor;
  private final long addrMinReadCursor;
//  private final long addrBuffer;
  private final long addrReadCursorCount;//int type
  private long addrReadCursors;

  private final Object[] slots;

  /**
   * create a LongHyperLoop with 512 slots in its internal buffer
   *
   */
  public GenericHyperLoop() {
    this(1024);
  }

  /**
   * create a LongHyperLoop.
   * <p>
   * contract: <p>
   *  1. nBufferSlots >=8  <p>
   *  2. nBufferSlots is a power of 2, and
   *  better if you can make nBufferSlots*8 a multiple of 4096
   *
   * <p>
   *
   * @param nBufferSlots the size of internal buffer in slot unit
   */
  public GenericHyperLoop(int nBufferSlots) {
    contract(()->nBufferSlots>=8);
    contract(()->Integer.bitCount(nBufferSlots)==1);
    this.nBufferSlots = nBufferSlots;
    this.bufferSlotMask = nBufferSlots - 1;
    //========================================================
    int requestedSize = SIZE_HYPERLOOP_BASE;
    addressRaw = systemAllocateMemory(requestedSize + SIZE_PAGE);

    addressHyperLoop = nextPageAlignedAddress(addressRaw);
    contract(() -> isPageAligned(addressHyperLoop));

    this.addrWriteCursor = addressHyperLoop
        + SIZE_CACHE_LINE_PADDING;
    contract(() -> isCacheLineAligned(addrWriteCursor));

    this.addrMinReadCursor = addrWriteCursor + SIZE_CACHE_LINE_PADDING;
    contract(() -> isCacheLineAligned(addrMinReadCursor));

    this.addrReadCursorCount = addrMinReadCursor + SIZE_CACHE_LINE_PADDING;
    contract(() -> isCacheLineAligned(addrReadCursorCount));

    this.addrReadCursors = addrReadCursorCount;
    contract(() -> isCacheLineAligned(addrReadCursors));

    //================slots
    slots = new Object[nBufferSlots];


    UNSAFE.putLong(addrWriteCursor, 0L);
    UNSAFE.putLong(addrMinReadCursor, 0L);
    UNSAFE.putInt(addrReadCursorCount,0);
  }

  /**
   * TODO: overload a long array version?
   * Send out an Object of type T to the {@link GenericHyperLoop}
   *
   * @param value - the value to be sent out to the {@link GenericHyperLoop}
   * @return - true if sent out successfully, or false if failed
   */
  public boolean trySend(T value) {
    long minReadCursor = UNSAFE.getLongVolatile(null,addrMinReadCursor);
    long writeCursor = UNSAFE.getLong(addrWriteCursor);
    if (writeCursor == (minReadCursor+nBufferSlots)) {
      // assume readCursorCount>0
      minReadCursor = UNSAFE.getLongVolatile(null, addrReadCursors);
      for (int i = 1; i < UNSAFE.getInt(addrReadCursorCount); i++) {
        long readCursor = UNSAFE.getLongVolatile(null, addrReadCursors
            - i*SIZE_CACHE_LINE_PADDING);
        minReadCursor = minReadCursor > readCursor ? readCursor : minReadCursor;
      }
      UNSAFE.putLong(addrMinReadCursor,minReadCursor);
      return false;
    }

    UNSAFE.putOrderedObject(slots,
        BASE+SCALE*(writeCursor & bufferSlotMask),value);
    UNSAFE.putOrderedLong(null,addrWriteCursor, writeCursor + 1);
    return true;
  }

  /**
   * variant of {@link #trySend(Object)}. <p>
   * It will block until the value has been sent out.
   */
  public void send(T value) {
    long minReadCursor = UNSAFE.getLongVolatile(null,addrMinReadCursor);
    long writeCursor = UNSAFE.getLong(addrWriteCursor);

    while (writeCursor == (minReadCursor+nBufferSlots)) {
      // assume readCursorCount>0
      minReadCursor = UNSAFE.getLongVolatile(null, addrReadCursors);
      for (int i = 1; i < UNSAFE.getInt(addrReadCursorCount); i++) {
        long readCursor = UNSAFE.getLongVolatile(null, addrReadCursors
            - i*SIZE_CACHE_LINE_PADDING);
        minReadCursor = minReadCursor > readCursor ? readCursor : minReadCursor;
      }
      UNSAFE.putLong(addrMinReadCursor,minReadCursor);
      Thread.yield();//harm latency but welcome to throughput
    }

    UNSAFE.putObject(slots,
        BASE+SCALE*(writeCursor & bufferSlotMask),value);
    UNSAFE.putLong(addrWriteCursor, writeCursor + 1);
    UNSAFE.storeFence();
  }


  @Override
  public void finalize() {
    systemFreeMemory(addressRaw);
  }


  public z.channel.ReceivePort<T> createReceivePort() {
    return this.new OutPort<T>();
  }

  /**
   * Note:
   * it is always hoped the values sent into LongHyperLoop could be consumed ASAP.
   *
   * TODO: need to handle the removal of OutPort dynamically
   */
  public final class OutPort<T> implements z.channel.ReceivePort<T> {
    private final long addrReadCursor;

    public OutPort() {
      synchronized(OutPort.class) {
        addrReadCursors += SIZE_CACHE_LINE_PADDING;
        this.addrReadCursor = addrReadCursors;
        //TODO: clear the readCursor
        UNSAFE.putLong(addrReadCursor,0L);
        UNSAFE.putInt(addrReadCursorCount,
            UNSAFE.getInt(addrReadCursorCount) + 1);
      }
    }

    public boolean isReceivable() {
      return UNSAFE.getLong(addrReadCursor) !=
          UNSAFE.getLongVolatile(null, addrWriteCursor);
    }

    public boolean notReceivable() {
      return UNSAFE.getLong(addrReadCursor) ==
          UNSAFE.getLongVolatile(null, addrWriteCursor);
    }

    //TODO: unchecked except to checked?
    /**
     * this {@link #tryReceive()} and {@link #notReceivable()} are another kind
     * style of combined APIs.<p>
     * Contrast to {@link #receive()},
     * you should first use {@link #notReceivable()} to ensure that you can
     * do the following {@link #tryReceive()}, otherwise you may get an
     * {@link IllegalStateException} when nothing could be received.
     *
     * @return
     */
    public T tryReceive() {
      long readCursor = UNSAFE.getLong(addrReadCursor);
      if (readCursor==UNSAFE.getLong(addrWriteCursor)) {
        throw new IllegalStateException("nothing to receive.");
      }
      //NOTE: we need a load fence to the element of slots, not slots itself
      T value = (T)UNSAFE.getObjectVolatile(slots,
          BASE + SCALE * (readCursor & bufferSlotMask));
      UNSAFE.putLong(addrReadCursor, readCursor+1);
      UNSAFE.storeFence();
      return value;
    }

    /**
     * Contrast to {@link #notReceivable()} + {@link #tryReceive()}, this call
     * will wait(block) until something can be received.<p>
     * So this call gives more higher throughput but harms latency.<p>
     * NOTE: This method is just for lazy men.<p>
     * You can use {@link #notReceivable()} + {@link #tryReceive()} with
     * Landz off-heap APIs to achieve more controllable
     * and low latency batch behavior.
     *
     */
    public T receive() {
      long readCursor = UNSAFE.getLong(addrReadCursor);
      while (readCursor==UNSAFE.getLongVolatile(null, addrWriteCursor)) {
        Thread.yield();
      }
      T value = (T)UNSAFE.getObjectVolatile(slots,
          BASE + SCALE * (readCursor & bufferSlotMask));
      UNSAFE.putLong(addrReadCursor, readCursor+1);
      UNSAFE.storeFence();
      return value;
    }

  }

}
