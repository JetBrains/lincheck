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

import static z.util.Contracts.contract;
import static z.util.Unsafes.*;


/**
 * Generic variant of {@link z.channel.MPMCQueue}.
 *
 */
public final class GenericMPMCQueue<T> implements Channel<T>, ReceivePort<T> {

  private static final int BASE  = UNSAFE.ARRAY_OBJECT_BASE_OFFSET;
  private static final int SCALE = UNSAFE.ARRAY_OBJECT_INDEX_SCALE;

  private static final int LONG_BASE  = UNSAFE.ARRAY_LONG_BASE_OFFSET;
  private static final int LONG_SCALE = UNSAFE.ARRAY_LONG_INDEX_SCALE;

//  private static final int SIZESHIFT_BUFFERSLOT = 4;//2*SIZE_LONG_TYPE
//  private static final int SIZE_LONG_TYPE = 8;

  private final long addressRaw;
  private final long addressMPMCQueue;

  private final int nBufferSlots;
  private final int bufferSlotMask;

  private final long addrWriteCursor;
  private final long addrReadCursor;

  private final Object[] slots;
  private final long[] slotSeqs;

//  //1 boolean(byte) per slot,true is un-pub-ed, false is pub-ed
//  private final long addrMarkBuffer;

  /**
   * @param nBufferSlots the size of internal buffer in slot unit
   */
  public GenericMPMCQueue(int nBufferSlots) {
    contract(()->nBufferSlots>=2);
    contract(()->Integer.bitCount(nBufferSlots)==1);
    this.nBufferSlots = nBufferSlots;
    this.bufferSlotMask = nBufferSlots - 1;
    //========================================================
    addressRaw = systemAllocateMemory(SIZE_CACHE_LINE_PADDING*4 + SIZE_CACHE_LINE);

    addressMPMCQueue = nextCacheLineAlignedAddress(addressRaw);
    contract(() -> isCacheLineAligned(addressMPMCQueue));

    this.addrWriteCursor = addressMPMCQueue + SIZE_CACHE_LINE_PADDING;
    contract(() -> isCacheLineAligned(addrWriteCursor));

    this.addrReadCursor = addrWriteCursor + SIZE_CACHE_LINE_PADDING;
    contract(() -> isCacheLineAligned(addrReadCursor));

    slots = new Object[nBufferSlots];

    slotSeqs = new long[nBufferSlots];
    for (int i = 0; i < nBufferSlots; i++) {
      UNSAFE.putLongVolatile(slotSeqs, LONG_BASE+LONG_SCALE*i, i);
    }

    UNSAFE.putLong(addrWriteCursor, 0L);
    UNSAFE.putLong(addrReadCursor, 0L);
  }

  public void send(T value) {
    while (!offer(value)){
      Thread.yield();//TODO
    }
  }

  public boolean trySend(T value) {
    return offer(value);
  }

  public T tryReceive() {
    return poll();
  }

  public T receive() {
    T v;
    while ( null==(v=poll()) ) {
      Thread.yield();//TODO
    }
    return v;
  }

  public boolean isReceivable() {
    throw
        new UnsupportedOperationException("isReceivable is not available now");
  }

  public boolean notReceivable() {
    throw
        new UnsupportedOperationException("notReceivable is not available now");
  }

  /**
   * To add one long value to current {@link z.channel.GenericMPMCQueue}.
   * The added long value will stored in the {@link z.channel.GenericMPMCQueue}
   * in FIFO order.
   * <p>
   * @return - true if successfully added to the currrent
   *           {@link z.channel.GenericMPMCQueue},
   *           or false if currrent {@link z.channel.GenericMPMCQueue}
   *           is full.
   */
  public boolean offer(T value) {
    long writeCursor, seqOffset;
    writeCursor = UNSAFE.getLongVolatile(null,addrWriteCursor);
    for (;;) {
      seqOffset = LONG_BASE+LONG_SCALE*(writeCursor & bufferSlotMask);
      long seq = UNSAFE.getLongVolatile(slotSeqs, seqOffset);
      long dif = seq - writeCursor;
      if (dif == 0) {
        if (UNSAFE.compareAndSwapLong(null,
            addrWriteCursor, writeCursor, writeCursor + 1))
          break;
      } else if (dif < 0) {
        return false;
      } else {
        writeCursor = UNSAFE.getLongVolatile(null,addrWriteCursor);
      }
    }
    UNSAFE.putOrderedObject(slots,
        BASE+SCALE*(writeCursor & bufferSlotMask), value);
    UNSAFE.putLongVolatile(slotSeqs, seqOffset, writeCursor + 1);

    return true;
  }

  /**
   * To Retrieves and removes one long value from current
   * {@link z.channel.GenericMPMCQueue}.
   *
   * This long value is sent before in FIFO order.
   * <p>
   * @return - one long value if currrent {@link z.channel.GenericMPMCQueue}
   *           holds at least one value,
   *           or null if currrent {@link z.channel.GenericMPMCQueue}
   *           is empty. Please use the null to check your result.
   */
  public T poll() {
    long readCursor, seqOffset;
    readCursor = UNSAFE.getLongVolatile(null,addrReadCursor);
    for (;;) {
      seqOffset = LONG_BASE+LONG_SCALE*(readCursor & bufferSlotMask);
      long seq = UNSAFE.getLongVolatile(slotSeqs, seqOffset);
      long dif = seq - (readCursor + 1);
      if (dif == 0) {
        if (UNSAFE.compareAndSwapLong(null,
            addrReadCursor, readCursor, readCursor + 1))
          break;
      } else if (dif < 0) {
        return null;
      } else {
        readCursor = UNSAFE.getLongVolatile(null, addrReadCursor);
      }
    }

    T value = (T)UNSAFE.getObjectVolatile(slots,
        BASE+SCALE*(readCursor & bufferSlotMask));
    UNSAFE.putLongVolatile(slotSeqs, seqOffset, readCursor + nBufferSlots);
    return value;
  }

  @Override
  public void finalize() {
    systemFreeMemory(addressRaw);
  }

}
