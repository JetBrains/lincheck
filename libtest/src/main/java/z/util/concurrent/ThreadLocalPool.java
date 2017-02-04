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

package z.util.concurrent;

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

import z.util.primitives.Ints;

import java.util.function.Supplier;

import static z.util.Contracts.contract;
import static z.util.Unsafes.currentThreadId;


/**
*
* ThreadLocalPool, is a pool for type T. This may be good for "heavy" object.
* But, ThreadLocalPool, as a "heavy" object container, itself is "heavy". And,
* this ThreadLocalPool does not support "recycle". Once, you need to recycle
* pooled objects, you may need to recycle often. Then, the sense of pool
* has been gone.
* <p>
* The better way is to estimate the maximum cursor of your ThreadLocalPool.
* However, you would get null when no object is available at calling point.
* You should check against this if needed.
* <p>
* Ideas:
*   1. too large pool is still not friendly to GC;
*
* <p>
* Note:
*   this ThreadLocalPool instance only support the long-living Thread
*   usage now, although this case may be improved in the future when some low level
*   hooking ready.
*
*/

public class ThreadLocalPool<T> implements AutoCloseable {

  private static final int MAX_SUPPORTED_THREAD_ID = 1024;//FIXME
  //TODO: false sharing
  private final ItemStack[] tlStacks = new ItemStack[MAX_SUPPORTED_THREAD_ID];

  private final int incrementStep;
  private final int capacity;
  private final Supplier<T> supplier;//return supplier.get();

  public ThreadLocalPool(int capacity, Supplier<T> supplier) {
    this(capacity, capacity, supplier);
  }

  public ThreadLocalPool(int capacity, int incrementStep, Supplier<T> supplier) {
    contract(
      () -> Ints.isPowerOfTwo(capacity)
          && Ints.isPowerOfTwo(incrementStep)
          && (incrementStep<=capacity) ,
      () -> new IllegalArgumentException("Now the capacity " +
            "is supported to be the power of 2 only."));//FIXME

    this.capacity      = capacity;
    this.incrementStep = incrementStep;
    this.supplier      = supplier;
  }


  /**
   * return one pooled item which contains type T object, or return null
   * immediately if no item is available.
   */
  public Item<T> item() {
    int tid = (int)currentThreadId();
    //FIXME as contract
    if (tid==MAX_SUPPORTED_THREAD_ID)
      throw new IllegalStateException(
          "the supported threadId should be less than "
              +MAX_SUPPORTED_THREAD_ID);

    ItemStack stack = tlStacks[tid];
    if (stack==null) {
      stack = new ItemStack(incrementStep, incrementStep!=capacity, supplier);
      tlStacks[tid] = stack;
    }

    if (stack.cursor ==0 && stack.notMaxCapacity) {
      if (capacity<=(stack.capacity+incrementStep)) {
        stack.enlarge(incrementStep);
        if (capacity==stack.capacity)
          stack.notMaxCapacity = false;
      }
    }

    return stack.pop();
  }

  @Override
  /**
   * release the pool's all resources
   */
  public void close() throws Exception {
    for (int i = 0; i < tlStacks.length; i++) {
      ItemStack stack = tlStacks[i];
      if (stack!=null) {
        Item<T>[] items = stack.items;
        for (int j = 0; j < items.length; j++) {
          Object obj = items[j].get();
          if (obj instanceof AutoCloseable)
            ((AutoCloseable)obj).close();
          items[j] = null;
        }
        tlStacks[i] = null;
      }
    }
  }

  public static class Item<T> implements AutoCloseable {
    T object;
    final ItemStack stack;

    Item(T object, ItemStack stack) {
      this.object = object;
      this.stack   = stack;
    }

    public T get() {
      return object;
    }

    @Override
    public void close() {
      stack.push(this);
    }
  }


  private static class ItemStack<T> {

    private final Supplier supplier;

    private Item<T>[] items;
    private int capacity;
    private int cursor;

    boolean notMaxCapacity;

    ItemStack(int initSize, boolean notMaxCapacity, Supplier<T> supplier) {
      this.supplier      = supplier;
      this.notMaxCapacity = notMaxCapacity;
      enlarge(initSize);
    }

    Item<T> pop() {
      if (cursor == 0) {
        return null;
      }
      return items[--cursor];
    }

    void push(Item item) {
      items[cursor++] = item;
    }

    void enlarge(int incrementStep) {
      int newCapacity = capacity+incrementStep;
      Item<T>[] newItems = new Item[newCapacity];
      if (items!=null) {
        System.arraycopy(items, 0, newItems, capacity, incrementStep);
      }
      items = newItems;
      capacity = newCapacity;
      cursor += incrementStep;
      //initialize the empty slot
      for (int i = 0; i < incrementStep; i++) {
        items[i]=new Item(supplier.get(), this);
      }
    }

  }

}

