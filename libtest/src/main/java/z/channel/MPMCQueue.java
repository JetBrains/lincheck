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
 * A bounded multi-producer/multi-consumer LongHyperLoop.
 * <p>
 * Different from MP Disruptor, it is done in "queue" style. That is, the out
 * element is only consumed by one of all consumers, not all of them
 * (broadcasting).
 * <p>
 * references implementation is Dmitry Vyukov's Bounded MPMC queue:
 * http://www.1024cores.net/home/lock-free-algorithms/queues/bounded-mpmc-queue
 * <p>
 * Note:<p>
 *   1. this is NOT a "totally lock-free" algorithm. But, it's still much
 * better than the lock-based algorithm.
 *   2. for convenience, now we use {@link Long#MIN_VALUE} to present NULL
 * to receive from {@link MPMCQueue}(otherwise we should an exception).
 *
 */
public class MPMCQueue {

  /**
   * for convenience, now we use {@link Long#MIN_VALUE} to present nothing could
   * be received from {@link MPMCQueue}(otherwise we should an exception).
   */
  public static final long NULL = Long.MIN_VALUE;
  private static final int SIZESHIFT_BUFFERSLOT = 4;//2*SIZE_LONG_TYPE
  private static final int SIZE_LONG_TYPE = 8;

  private final long addressRaw;
  private final long addressMPMCQueue;

  private final int nBufferSlots;
  private final int bufferSlotMask;

  private final long addrWriteCursor;
  private final long addrReadCursor;
  private final long addrBuffer;

//  //1 boolean(byte) per slot,true is un-pub-ed, false is pub-ed
//  private final long addrMarkBuffer;

  /**
   * @param nBufferSlots the size of internal buffer in slot unit
   */
  public MPMCQueue(int nBufferSlots) {
    contract(()->nBufferSlots>=2);
    contract(()->Integer.bitCount(nBufferSlots)==1);
    this.nBufferSlots = nBufferSlots;
    this.bufferSlotMask = nBufferSlots - 1;
    //========================================================
    int bufferSize = nBufferSlots<<SIZESHIFT_BUFFERSLOT;
    addressRaw = systemAllocateMemory(
        bufferSize + SIZE_CACHE_LINE_PADDING*4 + SIZE_CACHE_LINE);

    addressMPMCQueue = nextCacheLineAlignedAddress(addressRaw);
    contract(() -> isCacheLineAligned(addressMPMCQueue));

    this.addrWriteCursor = addressMPMCQueue + SIZE_CACHE_LINE_PADDING;
    contract(() -> isCacheLineAligned(addrWriteCursor));

    this.addrReadCursor = addrWriteCursor + SIZE_CACHE_LINE_PADDING;
    contract(() -> isCacheLineAligned(addrReadCursor));

    this.addrBuffer = addrReadCursor + SIZE_CACHE_LINE_PADDING;
    contract(() -> isCacheLineAligned(addrBuffer));

//    this.addrMarkBuffer = addrBuffer + bufferSize + SIZE_CACHE_LINE_PADDING;
//    contract(() -> isCacheLineAligned(addrMarkBuffer));

    //clear
    for (int i = 0; i < nBufferSlots; i++) {
      UNSAFE.putLong(
          addrBuffer + (i << SIZESHIFT_BUFFERSLOT)+SIZE_LONG_TYPE, i);
    }

    UNSAFE.putLong(addrWriteCursor, 0L);
    UNSAFE.putLong(addrReadCursor, 0L);
  }

  /**
   * To add one long value to current {@link MPMCQueue}.
   * The added long value will stored in the {@link MPMCQueue}
   * in FIFO order.
   * <p>
   * @return - true if successfully added to the currrent
   *           {@link MPMCQueue},
   *           or false if currrent {@link MPMCQueue}
   *           is full.
   */
  public boolean offer(long value) {
    long writeCursor, addrWriteIndex;
    writeCursor = UNSAFE.getLongVolatile(null,addrWriteCursor);
    for (;;) {
      addrWriteIndex = addrBuffer +
          ((writeCursor & bufferSlotMask) << SIZESHIFT_BUFFERSLOT);
      long seq = UNSAFE.getLongVolatile(null, addrWriteIndex+SIZE_LONG_TYPE);
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
    UNSAFE.putLongVolatile(null, addrWriteIndex,value);
    UNSAFE.putLongVolatile(null, addrWriteIndex + SIZE_LONG_TYPE,
        writeCursor + 1);

    return true;
  }

  /**
   * To Retrieves and removes one long value from current
   * {@link MPMCQueue}.
   *
   * This long value is sent before in FIFO order.
   * <p>
   * @return - one long value if currrent {@link MPMCQueue}
   *           holds at least one value,
   *           or {@link #NULL} if currrent {@link MPMCQueue}
   *           is empty. Please use the {@link #NULL} to check your result.
   */
  public long poll() {
    long readCursor, addrReadIndex;
    readCursor = UNSAFE.getLongVolatile(null,addrReadCursor);
    for (; ; ) {
      addrReadIndex = addrBuffer +
          ((readCursor & bufferSlotMask) << SIZESHIFT_BUFFERSLOT);
      long seq = UNSAFE.getLongVolatile(null, addrReadIndex + SIZE_LONG_TYPE);
      long dif = seq - (readCursor + 1);
      if (dif == 0) {
        if (UNSAFE.compareAndSwapLong(null,
            addrReadCursor, readCursor, readCursor + 1))
          break;
      } else if (dif < 0) {
        return NULL;
      } else {
        readCursor = UNSAFE.getLongVolatile(null, addrReadCursor);
      }
    }

    long value = UNSAFE.getLongVolatile(null, addrReadIndex);
    UNSAFE.putLongVolatile(null, addrReadIndex + SIZE_LONG_TYPE,
        readCursor + nBufferSlots);
    return value;
  }

  @Override
  public void finalize() {
    systemFreeMemory(addressRaw);
  }

}
