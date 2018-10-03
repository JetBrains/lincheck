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

package z.offheap.zmalloc;

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

import z.channel.MPMCQueue;
import z.util.SystemProperty;
import z.util.primitives.Longs;

import java.util.Optional;

import static z.util.Contracts.contract;
import static z.util.Unsafes.*;

/**
 * "zmalloc" - Landz's off-heap direct memory allocator.
 * <p>
 * It is designed to have high performance with scalable hardware support,
 * zero garbage generation and built-in statistics in the pure Java.
 *
 * <p>
 * zmalloc concepts:
 * <p> GlobalPool: a memory area which the allocator can use.
 * <p> ThreadLocalPool: a thread local memory area for efficient chunk
 *                      allocation.
 *                      It will request/release memories from/to GlobalPool
 *                      when necessary.
 * <p> Page: a up-level internal allocation unit which is larger than a Chunk,
 *           as the chunk cache for some specific SizeType.
 *           it is always in 3 states: free, partial, full.
 * <p> Chunk: just a continuous memory block, basic allocation unit in zmalloc,
 *        which is in a specific SizeType
 * <p> SizeClass: Chunks are only allocated in one size of SizeClass
 *
 * <p>
 *
 * <b>Designer Note:</b>
 *
 * <p>
 * Landz's zmalloc is designed from scratch to avoid any license problem.
 * There are three public reference for the inspirations:
 * <ul>
 * <li> one article about Memcached's slab allocator
 * (http://nosql.mypopescu.com/post/13506116892/memcached-internals-memory-
 *  allocation-eviction);
 * <li> one book chapter about linux kernel slab allocator(detailed link);
 * <li> one paper about lock-free memory allocator by Oracle's Dave Dice
 *    (detailed link);
 * </ul>
 * <p>
 * Generally, the zmalloc is more like as slab allocator.
 * It has the possible to meet "pathological case"
 * (There are workaround suggestions). But as my investigate to public bug
 * reports, ptmalloc and tcmalloc also has this problem.
 *
 * <p>
 * The main structure of current impl is two-level: ZMPage, and ZMChunck.
 * Because the page and chunk is common, so I ZM- prefix to make them
 * distinguished with others. And more additions, the naming in Allocator class
 * is C-style, in that: <br/>
 * <ul>
 * <li> there are many new concepts in this allocator field,
 * then full camel-case naming is hard to read, short camel-case naming is
 * talking nothing; <br/>
 * <li> to warning the developer we are in dangerous area.
 * </ul>
 * <p>
 * ZMPage: is the container of the ZMChuncks, which is 2MB now.
 * 2MB is not random chosen size, which is the default large Page size in
 * Linux. This limits current max size of chunk can be allocated by zmalloc
 * <br/>(NOTE: There are several ways for solving this. I may push a version to
 * support a larger size in a near future.)
 *
 * <p>
 * Because zmalloc makes the page to the same size. The page can be shared
 * between threadlocal pool and global pool.
 * This is different to the size of slab of Memcached as my understanding to
 * that reference. (I don't comment on which is better in that the references
 * can not drive us to any conclusion.)
 *
 * <p>
 * ZMChunck is the final allocated memory area for use. Once a ZMPage is used
 * to allocate ZMChuncks, then ZMPage is assigned to a sizeClass which indicated
 * this ZMPage is for which size of ZMChunck. A sizeClassIndex(or sci for short
 * in sources) is for indexing one size of the pre-allocate table(or said a
 * continuous memory area in the offheap area). This is just for speeding and
 * improving memory locality(all this can be done by on-heap objects).
 *
 * <p>
 * ZMPage contains the metadata of ZMChunk. This is different to that the
 * jemalloc uses RBTree. (I don't comment on which is better in that the
 * references can not drive us to any conclusion.)
 *
 * One design tip with the ZMPage is that, the ZMPage is 2MB aligned in design,
 * it make to reason one ZMChunk belongs to which ZMPage in two of fastest
 * instructions in all of CPUs):
 *
 * <pre>
 * long addressPage = addressChunk-(addressChunk&(SIZE_ZMPAGE-1));
 * </pre>
 *
 * see this statement in {@link Allocator#free}
 *
 * <p>
 * This gives big benefits: ZMChunk doesn't need to store any metadata. This
 * shortens the critical path and decide how the way landz manage the metadata.
 *
 * <p>
 * ZMPages are from a global pages pool, then a ZMPage should be in a thread
 * pool to use. When ZMPage in the free state, it is can be freed to global pool
 * when some conditions.
 *
 * <p>
 * The operations inside of threadLocal pool, as the name indicated, are thread safe.
 *
 * <p>
 * The Operations from threadLocal pool to global pool use the landz's
 * {@link MPMCQueue}, which provide a high-throughput/low-latency lock-free
 * bounded queue.
 *
 * <p>
 * One important side of thread-safe design to allocator is the cross-thread
 * free invocation. ZMalloc solves this like this:
 * <ul>
 * <li> metadata records the thread's tid when allocated, call ownedTid;
 * <li> if free thread's tid found not match with ownedTid, then zmalloc just
 *      put the return that chunk to ownedTid's remote freed queue.
 * <li> when some conditions happens, the remote freed queue will be drained for
 *      use;
 * <li> remote freed queue is a careful design lock-free/ABA-free MPSC queue
 *      by Landz itself;
 * </ul>
 *
 * <p>
 * more detail comes here...
 *
 * <p>
 * Namings in the methods:
 * <ul>
 * <li> pg_ is for page operations;
 * <li> gp_ is for global pool operations;
 * <li> tlp_ is for threadlocal pool operations;
 *
 * </ul>
 *
 *
 */
public class Allocator {

  /**
   * Note: most of variables are stored in the long type for future's extensions
   *       and/or 8B alignment, but we may only use the int/byte-size slot when
   *       long-size is not needed.
   */

  private static final long NULL_ADDRESS = 0L;

//  private static final int SIZE_LONG_TYPE = 8;
//  private static final int SIZE_INT_TYPE  = 4;

  /**
   * addressZM: zmalloc metadata area + global pool area
   * arrange 8MB VM metadata area for zmalloc globals, may be
   * reallocated in the future;
   */
  private static final long addressRaw;
  private static final long addressZM;

  private static final long SIZE_METADATA_ZMALLOC = 1<<23;


  /**
   *  35 kinds of SizeClass now
   */
  private static final long addressSizeClassInfo;
  private static final int TLPHEAD_TO_SIZECLASSINFO = 4*1024;

  /**
   * off-heap ThreadLocalPools area desgin is an optimization for
   * java ThreadLocal. It provide a better TLS implementation for zmalloc
   * than ThreadLocal in long-running thread usage, like thread pool.
   *
   * But this design can not support that applications like to spawn huge mounts
   * of short-living thread. Becuase threads can be generated infinitely, but
   * off-heap is finally limited.
   *
   * By default, the first 3k-1 thread (id<3072) can have a TLP slot in the
   * managed off heap. For the later spawning threads, it will go back to use
   * slower {@link ThreadLocal}.
   *
   * TODO: j.l.ThreadLocal backed TLP schema has not implemented now...
   *
   * addressTLPs: address for ThreadLocalPools
   * structure:
   *   (.. CacheLine aligned)
   *   long      - number of TLPs
   *   [..to nextCacheline start]
   *   [..to nextCacheline start]
   *   TLPStates
   *   (note: this area has one-shot false sharing for long-running threads)
   *   byte - 0..
   *   byte - 1..
   *   byte - 2..
   *   byte - ..
   *   byte - 3071..
   *   [..to next 4096 offset start]
   *   (addressTLP#0)
   *   address[36]  - AvailablePages
   *                    - AvailablePage[0] - 3*8B: head, tail, num
   *                    ..
   *                    - AvailablePage[35]
   *   address   - freePagesHead
   *   address   - freePagesTail
   *   long      - NumFreePages
   *   [..to 1024 offset start]
   *   address   - remoteFreedChunksHead
   *   [..to nextCacheline start]
   *   [..to nextCacheline start]
   *   address   - remoteFreedChunksTail
   *   [..to nextCacheline start]
   *   [..to nextCacheline start]
   *   address   - remoteFreedChunksDummy
   *   [..to nextCacheline start]
   *   [..to nextCacheline start]
   *
   *   address[] - batchRequestedPages (1-?) TODO: not used
   *               the TLP can request pages from GP in batch for performance.
   *               here, batchRequestedPages is for storing the returned batch
   *               requested pages. Now we support to 1 to ? varied number of
   *               pages in one TLP. It is possible to support larger number of
   *               pages after enlarging the TLP metadata area.
   *               attention: this area is *transient*, it is the related APIs'
   *               clients' responsibility to use this at its immediate
   *               availability.
   *
   *   [..padding to 2048]
   *   (addressTLP#1)
   *   ..
   */
  private static final long addressTLPHEAD;
  private static final int TLPHEAD_NUMTLPS_OFFSET = 0;

  private static final int TLPSTATES_TO_TLPHEAD_OFFSET = SIZE_CACHE_LINE_PADDING;

  private static final long addressTLPStates;
  private static final int SIZE_TLPStates_ARRAY_LENGTH = 3*1024;

  private static final int TLPS_TO_TLPHEAD_OFFSET = 4*1024;

  private static final long addressTLPs;
  private static final int TLP_ITEM_SIZE = 2*1024;


  private static final int TLP_AVAILABLEPAGES_OFFSET = 0;
  private static final int TLP_AVAILABLEPAGES_ITEM_SIZE = 3*SIZE_LONG_TYPE;
  private static final int TLP_AVAILABLEPAGES_ARRAYLENGTH = 36;

  private static final int TLP_FREEPAGESHEAD_OFFSET =
      TLP_AVAILABLEPAGES_ITEM_SIZE * TLP_AVAILABLEPAGES_ARRAYLENGTH;
  private static final int TLP_FREEPAGESTAIL_OFFSET =
      TLP_FREEPAGESHEAD_OFFSET + SIZE_LONG_TYPE;
  private static final int TLP_NUMFREEPAGES_OFFSET =
      TLP_FREEPAGESTAIL_OFFSET + SIZE_LONG_TYPE;

  private static final int TLP_REMOTEFREEDCHUNKS_HEAD_OFFSET = 1024;
  private static final int TLP_REMOTEFREEDCHUNKS_TAIL_OFFSET =
      TLP_REMOTEFREEDCHUNKS_HEAD_OFFSET + SIZE_CACHE_LINE_PADDING;
  private static final int TLP_REMOTEFREEDCHUNKS_DUMMY_OFFSET =
      TLP_REMOTEFREEDCHUNKS_TAIL_OFFSET + SIZE_CACHE_LINE_PADDING;

  private static final int TLP_BATCHREQUESTEDPAGES_OFFSET =
      TLP_REMOTEFREEDCHUNKS_DUMMY_OFFSET + SIZE_CACHE_LINE_PADDING;


  /**
   * change this threshold value larger if you want more aggressive cache
   */
  private static int freePagesNumThreshold;
  /**
   * change this return value smaller if you want more aggressive cache
   */
  private static int freePagesNumToReturn;

  private static final int FREEPAGES_NUM_THRESHOLD_DEFAULT = 64;
  private static final int FREEPAGES_NUM_TORETURN_DEFAULT = 32;

  //TODO: only use this optimization after enabling an configuration option
  //Note: this can reduce the request cost more, but cause much initial
  //      memory footprint
  private static final int NUM_BATCHREQUESTEDPAGES_DEFAULT = 4;//or 8? 16?
  /**
   * (..assumed Cacheline aligned for fist page)
   * structure:
   *   address - availableChunks
   *   long    - numAvailableChunks
   *   address - nextPage
   *   address - prevPage
   *   address - nextFreePage
   *   address - prevFreePage
   *   long    - numMaxChunks
   *   int     - sizeClass(int)
   *   int     - tid(long)
   *   (...padding to next cache line)
   */
  private static final long SHIFT_SIZE_ZMPAGE = 21;
  private static final long SIZE_ZMPAGE       = 1<<SHIFT_SIZE_ZMPAGE;

  //FIXME: some in this head should be in cache line padding
  private static final int ZMPAGE_AVAILABLECHUNKS_OFFSET    = 0;
  private static final int ZMPAGE_NUMAVAILABLECHUNKS_OFFSET = 1*SIZE_LONG_TYPE;
  private static final int ZMPAGE_NEXTPAGE_OFFSET           = 2*SIZE_LONG_TYPE;
  private static final int ZMPAGE_PREVPAGE_OFFSET           = 3*SIZE_LONG_TYPE;
  private static final int ZMPAGE_NEXTFREEPAGE_OFFSET       = 4*SIZE_LONG_TYPE;
  private static final int ZMPAGE_PREVFREEPAGE_OFFSET       = 5*SIZE_LONG_TYPE;
  private static final int ZMPAGE_NUMMAXCHUNKS_OFFSET       = 6*SIZE_LONG_TYPE;
  private static final int ZMPAGE_SIZECLASSINDEX_OFFSET     = 7*SIZE_LONG_TYPE;
  private static final int ZMPAGE_TID_OFFSET                = 7*SIZE_LONG_TYPE
                                                               +SIZE_INT_TYPE;
  //FIXME: this offset should be in cache line padding
  private static final int ZMPAGE_RAWCHUNK_OFFSET           = SIZE_CACHE_LINE;

  /**
   * TODO: now > {@link #ZMPAGE_MAX_CHUNK_SIZE} is not supported
   */
  private static final int ZMPAGE_MAX_CHUNK_SIZE =
      (int) SIZE_ZMPAGE-ZMPAGE_RAWCHUNK_OFFSET;

  /**
   * 1GB VM or System for initial off heap pool, can grow into
   * maximum 50% of total memory
   * <p>
   *
   * This adjusting could be avoided by fixing the #ZMALLOC_INITIAL_POOLSIZE
   *   same to the #ZMALLOC_MAX_POOLSIZE
   *
   *  <p>
   *
   * NOTE:
   *   1. 1GB is just for VM, not physical memory, but set your System
   *      Property for avoiding kinds of problems for small servers.
   *   2. we make the "effective" address of Global Pool(not including
   *      the metadata) page-aligned for kinds of goods.
   *   3. only to access your requested memory area, otherwise you may
   *      crash JVM!
   *
   *  structure:
   *    long  - addressAvailablePages
   *    long  - NumAvailablePages
   *
   *
   */
  private static final long addressGP;

  //TODO: add a dynamic change method?(note this may cost too long time or fail)
  private static final long sizeGP;
  private static final long totalAvailablepages;

  //we use the last 1-cacheline-padded slots before GP for GP's meta head
  private static final int GPHEAD_OFFSET = -1*SIZE_CACHE_LINE_PADDING;

//  private static final long addressGPHead;
  private static final long addressGPHead_NumAvailablePages;

  private static final MPMCQueue globalPool;


  static {
    //config kinds of options
    freePagesNumThreshold = Integer.parseInt(
        Optional
            .ofNullable(SystemProperty.ZMALLOC_FREEPAGES_NUM_THRESHOLD.value())
            .orElse(String.valueOf(FREEPAGES_NUM_THRESHOLD_DEFAULT)));

    freePagesNumToReturn  = Integer.parseInt(
        Optional
            .ofNullable(SystemProperty.ZMALLOC_FREEPAGES_NUM_TORETURN.value())
            .orElse(String.valueOf(FREEPAGES_NUM_TORETURN_DEFAULT)));


    long initialGPSize = Long.parseLong(
        Optional
            .ofNullable(SystemProperty.ZMALLOC_INITIAL_POOLSIZE.value())
            .orElse("1024"));

    contract(
        () -> Longs.isPowerOfTwo(initialGPSize),
        () -> new IllegalArgumentException("Now the size of global pool " +
            "is supported to be the power of 2 only."));//FIXME

    sizeGP = initialGPSize * 1024 * 1024;//initialGPSize is in MB
    totalAvailablepages = sizeGP/SIZE_ZMPAGE;

    /* TECH NOTE:
     *   the malloc-ed VM will be included a metadata head (at least for linux
     *   glibc's). For mmap-ed block(so-called large chunk),
     *   this head's size is 2*SIZE_SZ, 16 in x86-64, or 8 in x86.
     *   So, the glibc's malloc allocated memory is not page aligned in default.
     */
    addressRaw = systemAllocateMemory(
          SIZE_METADATA_ZMALLOC
        + sizeGP
        + SIZE_ZMPAGE); //should align to zmalloc page
    addressZM = nextZMPageAlignedAddress(addressRaw);
    contract(()-> isZMPageAligned(addressZM));

    /**
     * NOTE:
     *   1. 8B aligned;
     *   2. read-only
     */
    addressSizeClassInfo = addressZM;
    onAddress(addressSizeClassInfo)
            .put(8)                   //  0
            .followBy(16)             //  1
            .followBy(24)             //  2
            .followBy(32)             //  3
            .followBy(48)             //  4
            .followBy(64)             //  5
            .followBy(96)             //  6
            .followBy(128)            //  7
            .followBy(192)            //  8
            .followBy(256)            //  9
            .followBy(384)            //  10
            .followBy(512)            //  11
            .followBy(768)            //  12
            .followBy(1024)           //  13
            .followBy(1536)           //  14
            .followBy(2048)           //  15
            .followBy(3072)           //  16
            .followBy(4096)           //  17
            .followBy(6144)           //  18
            .followBy(8192)           //  19
            .followBy(12288)          //  20
            .followBy(16384)          //  21
            .followBy(24576)          //  22
            .followBy(32768)          //  23
            .followBy(49152)          //  24
            .followBy(65536)          //  25
            .followBy(98304)          //  26
            .followBy(131072)         //  27
            .followBy(196608)         //  28
            .followBy(262144)         //  29
            .followBy(393216)         //  30
            .followBy(524288)         //  31
            .followBy(786432)         //  32
            .followBy(1048576)        //  33
            .followBy(1572864)        //  34
            .followBy(ZMPAGE_MAX_CHUNK_SIZE); //  35

    addressTLPHEAD = addressSizeClassInfo + TLPHEAD_TO_SIZECLASSINFO;

    // addressTLPHEAD+TLPHEAD_NUMTLPS_OFFSET->addressTLPHEAD,
    //   in that TLPHEAD_NUMTLPS_OFFSET=0
    onAddress(addressTLPHEAD).put(0);//initial num counter for TLPs

    addressTLPStates = addressTLPHEAD + TLPSTATES_TO_TLPHEAD_OFFSET;
    //initialize TLPStates
    for (int i = 0; i < SIZE_TLPStates_ARRAY_LENGTH; i++) {
       UNSAFE.putByte(addressTLPStates+i,(byte)0);
    }

    //only initialize the main thread's ThreadLocalPools
    addressTLPs = addressTLPHEAD + TLPS_TO_TLPHEAD_OFFSET;
    contract(()-> is4096Aligned(addressTLPs));

    addressGP = addressZM + SIZE_METADATA_ZMALLOC;
    contract(()-> isPageAligned(addressGP));

    addressGPHead_NumAvailablePages = addressGP + GPHEAD_OFFSET;

    globalPool = new MPMCQueue((int)(initialGPSize/2));


    //initialize the GP
    gp_ini();

    //NOTE: this hook will cause buffer finalization panic.
    //      Although it is paintless, but we can just rely on OS for cleaning.
//    Runtime.getRuntime().addShutdownHook(
//        new Thread(()->systemFreeMemory(addressRaw)));

    //one-shot fence?
    UNSAFE.storeFence();
  }



  //============================================================================
  //zmalloc main APIs

  /**
   * Note: the allocated memory is not guarantee to be zero-ed. That is.
   *       it may contain any garbage.
   *
   * <p>
   * contract: 0 < sizeOfBytes <= 1536k (may change in the future)
   * <p> @param sizeOfBytes - the size which you want to request, in bytes
   * <p> @return - the address of your requested chunk, or NULL_ADDRESS(0L) if
   *             the allocator can not fulfil your request.
   */
  public static final long allocate(long sizeOfBytes) {
    int sci = sizeClassIndex((int) sizeOfBytes);
    //TODO: add contract here
    long tid = currentThreadId();

    //ensure the tlp area has been initialized
    if (UNSAFE.getByte(addressTLPStates + tid) ==0)
      tlp_ini(tid);

    //prepare TLP variables
    long addressTLP = addressTLPs + TLP_ITEM_SIZE *tid;
    long addrAvailablePages = addressTLP + TLP_AVAILABLEPAGES_OFFSET;
    long addrAvailablePageHead = addrAvailablePages +
        sci* TLP_AVAILABLEPAGES_ITEM_SIZE;
    long addrAvailablePageTail = addrAvailablePageHead + SIZE_LONG_TYPE;
    long addrNumAvailablePage  = addrAvailablePageTail + SIZE_LONG_TYPE;
    long numAvailablePage = UNSAFE.getLong(addrNumAvailablePage);


    long page;
    if (numAvailablePage!=0) {
      page = UNSAFE.getAddress(addrAvailablePageHead);

      boolean isFreePage =
          UNSAFE.getInt(page+ZMPAGE_NUMAVAILABLECHUNKS_OFFSET)==
              UNSAFE.getInt(page+ZMPAGE_NUMMAXCHUNKS_OFFSET);
//      FIXME: merged with next branch's pop?
//      chunk = pg_AvailableChunks_pop(page);

      if(isFreePage) {
        //meets free page, but now it is not
        long addrFreePagesHead = addressTLP + TLP_FREEPAGESHEAD_OFFSET;
        long addrFreePagesTail = addressTLP + TLP_FREEPAGESTAIL_OFFSET;
        long addrNumFreePages  = addressTLP + TLP_NUMFREEPAGES_OFFSET;
        tlp_FreePages_remove(
            addrFreePagesHead, addrFreePagesTail, addrNumFreePages, page);
      }
    } else {
      page = gp_Page_poll();

      //check with RemoteFreedChunks
      //TODO: do we have/need more better flush points?
      long addrRemoteFreedChunksHead =
          addressTLP + TLP_REMOTEFREEDCHUNKS_HEAD_OFFSET;
      long addrRemoteFreedChunksTail =
          addressTLP + TLP_REMOTEFREEDCHUNKS_TAIL_OFFSET;
      long addrRemoteFreedChunksDummy =
          addressTLP + TLP_REMOTEFREEDCHUNKS_DUMMY_OFFSET;
      if (page == NULL_ADDRESS) {
        //find in RemoteFreedChunks
        long c;
        while (NULL_ADDRESS != (c=tlp_RemoteFreedChunksHead_remove(
            addrRemoteFreedChunksHead,
            addrRemoteFreedChunksTail,
            addrRemoteFreedChunksDummy)) ) {
          long p = c-(c&(SIZE_ZMPAGE-1));
          if (sci==UNSAFE.getInt(p + ZMPAGE_SIZECLASSINDEX_OFFSET)) {
            return c;
          } else {
            free(c);
          }
        }
      } else {
        //flush whole RemoteFreedChunks queue
        long c;
        while (NULL_ADDRESS != (c=tlp_RemoteFreedChunksHead_remove(
            addrRemoteFreedChunksHead,
            addrRemoteFreedChunksTail,
            addrRemoteFreedChunksDummy)) ) {
          free(c);
        }
      }

      pg_setupPage(page, sci, tid);
      tlp_AvailablePages_addToHead(addrAvailablePageHead, page);
      //new page should be a freePage as well
//      tlp_FreePages_addToHead(addrFreePagesHead, page, addrNumFreePages);

    }

    //contract: page != NULL_ADDRESS
    long chunk = pg_AvailableChunks_pop(page);

    //we guarantee that we have chunks iff we have free pages
    //so we should guarantee the invariant: chunk != NULL_ADDRESS
    if (UNSAFE.getAddress(page) == NULL_ADDRESS) {
      //meets full page(in head), just remove it
       tlp_AvailablePages_remove(addrAvailablePageHead,
           UNSAFE.getAddress(addrAvailablePageHead));
    }
    return chunk;
  }

  /**
   * contract:
   *   the want-to-be-freed addressChunk should be allocated by
   *   {@link z.offheap.zmalloc.Allocator#allocate(long)}.
   */
  public static final void free(long addressChunk) {
    if (addressChunk<=0)
      throw new IllegalArgumentException(
          "addressChunk argument can't be less than zero.");

    long tid = currentThreadId();
    long addressPage = addressChunk-(addressChunk&(SIZE_ZMPAGE-1));
    int sci = UNSAFE.getInt(addressPage + ZMPAGE_SIZECLASSINDEX_OFFSET);
    int ownedTid = UNSAFE.getInt(addressPage + ZMPAGE_TID_OFFSET);
    //the chunk is remote freed
    if (tid!=ownedTid) {
      long addrRemoteFreedChunksTail = addressTLPs + TLP_ITEM_SIZE *ownedTid
          + TLP_REMOTEFREEDCHUNKS_TAIL_OFFSET;
      tlp_RemoteFreedChunksHead_add(addrRemoteFreedChunksTail,addressChunk);
      return;
    }

    boolean isFullPage =
        UNSAFE.getInt(addressPage+ZMPAGE_NUMAVAILABLECHUNKS_OFFSET)==0;

    pg_AvailableChunks_push(addressPage,addressChunk);

    if (isFullPage) {
      tlp_AvailablePages_addToTail(tid, sci, addressPage);
    }
    //is free page
    if (UNSAFE.getInt(addressPage+ZMPAGE_NUMAVAILABLECHUNKS_OFFSET)==
        UNSAFE.getInt(addressPage+ZMPAGE_NUMMAXCHUNKS_OFFSET)) {
      //TODO: add an option to configure whether to return freePages to GP?

      long addressTLP        = addressTLPs + TLP_ITEM_SIZE *tid;
      long addrFreePagesHead = addressTLP + TLP_FREEPAGESHEAD_OFFSET;
      long addrNumFreePages  = addressTLP + TLP_NUMFREEPAGES_OFFSET;

      tlp_FreePages_addToHead(addrFreePagesHead, addressPage, addrNumFreePages);

      if (UNSAFE.getLong(addrNumFreePages) > freePagesNumThreshold) {
        //TODO: add batch return method?
        //NOTE: freePages include different sizeClass's pages
        //TODO: separate the freePages to different sizeClasses?
        long addrFreePagesTail = addrFreePagesHead + SIZE_LONG_TYPE;

        for (int i=0;i< freePagesNumToReturn;i++) {
          //remove from head
          long head = UNSAFE.getAddress(addrFreePagesHead);
          //here contract: fpHead!=NULL_ADDRESS
          tlp_FreePages_remove(
              addrFreePagesHead, addrFreePagesTail, addrNumFreePages, head);

          long addrAvailablePageHead = addressTLP + TLP_AVAILABLEPAGES_OFFSET +
              UNSAFE.getInt(head + ZMPAGE_SIZECLASSINDEX_OFFSET)
                  * TLP_AVAILABLEPAGES_ITEM_SIZE;
          //FIXME: addrAvailablePageHead is sci-based,
          //       and then tlp_AvailablePages_remove is error-prone
          tlp_AvailablePages_remove(addrAvailablePageHead,head);

          gp_Page_offer(head);
        }
      }
    }

  }

  //============================================================================
  //internal operations

  //============================================================================
  //internal utils
  private static final long nextZMPageAlignedAddress(long address) {
    return address-(address&(SIZE_ZMPAGE-1))+SIZE_ZMPAGE;
  }

  private static final boolean isZMPageAligned(long address) {
    return (address&(SIZE_ZMPAGE-1))==0;
  }

  private static final long next4096AlignedAddress(long address) {
    return address-(address&4095)+4096;
  }

  private static final boolean is4096Aligned(long address) {
    return (address&4095)==0;
  }

//  private static int[] sizeClassTable0;
//  private static int[] sizeClassTable1;
//  private static int[] sizeClassTable2;
//
//  /**
//   *
//   * XXX: although this table based way beats sizeClassIndex0 in micro-bench,
//   *      it should be tested against real world usage.
//   *
//   */
//  public static final int sizeClassIndex(int sizeOfBytes) {
//    if (sizeOfBytes<=0)
//      return 0;
//
//    sizeOfBytes--;
//    int level0 = sizeOfBytes >>> 9;
//    int level1 = sizeOfBytes >>> 15;
//    int level2 = sizeOfBytes >>> 21;
//
//    if (level0==0) {
//      return sizeClassTable0[sizeOfBytes>>>2];
//    } else if(level1==0) {
//      return sizeClassTable1[sizeOfBytes>>>8];
//    } else if(level2==0) {
//      return sizeClassTable2[sizeOfBytes>>>14];
//    } else {
//      return 34;
//    }
//  }
//
//  private static final void createSizeClassLookupTable() {
//    sizeClassTable0 = new int[128];//0 - 511
//    sizeClassTable1 = new int[128];//512 - 32k-1
//    sizeClassTable2 = new int[128];//32k - 2M-1
//
//    for (int i = 4; i <= 512; i+=4) {
//        sizeClassTable0[(i-1)>>>2] = sizeClassIndex0(i);
//    }
//
//    for (int i = 256; i <= 32*1024; i+=256) {
//      sizeClassTable1[(i-1)>>>8] = sizeClassIndex0(i);
//    }
//
//    for (int i = 16*1024; i <= 2*1024*1024; i+=(16*1024)) {
//      sizeClassTable2[(i-1)>>>14] = sizeClassIndex0(i);
//    }
//  }

  /**
   * NOTE: now sizeOfBytes > {@link #ZMPAGE_MAX_CHUNK_SIZE} is not supported
   */
  private static final int sizeClassIndex(int sizeOfBytes) {
    //contract(()->sizeOfBytes!=0);
    if (sizeOfBytes>1572864) {
      //TODO: do we support {@link #ZMPAGE_MAX_CHUNK_SIZE} to public?
      return 35;
    }else if (sizeOfBytes>16) {
      int clg = (63-Long.numberOfLeadingZeros(sizeOfBytes));
      int upSizeClass1 = 1<<clg;
      int upSizeClass2 = (1<<clg)+(1<<(clg-1));

      if (sizeOfBytes == upSizeClass1) {
        return (clg-3)*2-1;
      } else if (sizeOfBytes > upSizeClass2) {
        return (clg-3)*2+1;
      } else {
        return (clg-3)*2;
      }
    } else if (sizeOfBytes>8) {
      return 1;
    } else {
      return 0;
    }
  }

  //============================================================================
  //zmalloc Page operations
  private static final void pg_setupPage(long addressPage,
                                         int sizeClassIndex,
                                         long tid) {
    int sizeClass = UNSAFE.getInt(addressSizeClassInfo+
        sizeClassIndex*SIZE_INT_TYPE);
    int numMaxAvailableChunks = ZMPAGE_MAX_CHUNK_SIZE/sizeClass;
    /*
     *reset the availableChunks to NULL ? do not need to reset this field here
     * if reset done in tlp_ini
     */
//    UNSAFE.putAddress(addressPage+ZMPAGE_NEXTPAGE_OFFSET,0);
    UNSAFE.putAddress(addressPage, NULL_ADDRESS);

    long addressRawChunks = addressPage+ZMPAGE_RAWCHUNK_OFFSET;
    for (int i = 0; i < numMaxAvailableChunks; i++) {
      long chunk = addressRawChunks+i*sizeClass;
      pg_AvailableChunks_push(addressPage, chunk);
    }
    UNSAFE.putInt(addressPage + ZMPAGE_NUMAVAILABLECHUNKS_OFFSET,
        numMaxAvailableChunks);
    UNSAFE.putInt(addressPage + ZMPAGE_NUMMAXCHUNKS_OFFSET,
        numMaxAvailableChunks);
    UNSAFE.putInt(addressPage + ZMPAGE_SIZECLASSINDEX_OFFSET,
        sizeClassIndex);
    //FIXME: tid is long type, but now we only support integer number tid
    UNSAFE.putInt(addressPage + ZMPAGE_TID_OFFSET, (int) tid);
  }

  private static final void pg_AvailableChunks_push(long addressPage,
                                                    long addressChunk) {
    //availableChunks = addressPage
    long head = UNSAFE.getAddress(addressPage);
    UNSAFE.putAddress(addressPage, addressChunk);
    UNSAFE.putAddress(addressChunk, head);

    long numAvailableChunks = addressPage+ZMPAGE_NUMAVAILABLECHUNKS_OFFSET;
    UNSAFE.putInt(numAvailableChunks, UNSAFE.getInt(numAvailableChunks) + 1);
  }

  private static final long pg_AvailableChunks_pop(long addressPage) {
    //long availableChunks = addressPage+ZMPAGE_AVAILABLECHUNKS_OFFSET;
    long head = UNSAFE.getAddress(addressPage);
    if (head != NULL_ADDRESS) {
      UNSAFE.putAddress(addressPage, UNSAFE.getAddress(head));

      long numAvailableChunks = addressPage+ZMPAGE_NUMAVAILABLECHUNKS_OFFSET;
      UNSAFE.putInt(numAvailableChunks, UNSAFE.getInt(numAvailableChunks) - 1);
    }//meet a full zmalloc page when head ==0L
    return head;
  }

  //============================================================================
  //GP operations
  private static final void gp_ini() {
    for (long i = 0; i < totalAvailablepages; i++) {
      long availableGPZMPage = addressGP + i*SIZE_ZMPAGE;
      gp_Page_offer(availableGPZMPage);
    }
  }

  /**
   * NOTE:<p>
   *   the return zmalloc page is raw, it is the responsibility of TLP to
   *   setup the returned page for its use.
   *
   * @return the address of raw zmalloc page
   */
  private static final long gp_Page_poll() {
    long page = globalPool.poll();
    if (page!= MPMCQueue.NULL) {
      UNSAFE.getAndAddLong(null, addressGPHead_NumAvailablePages, -1L);
      return page;
    } else {
      return NULL_ADDRESS;
    }
  }

  private static final void gp_Page_offer(long addressFreePage) {
//    int nRetries = 3;//TODO
//    for (int i = 0; i < nRetries; i++) {
      if (globalPool.offer(addressFreePage)) {
        UNSAFE.getAndAddLong(null, addressGPHead_NumAvailablePages, 1L);
        return;
//      }
    }
    throw new RuntimeException("can not offer the page to the global pool, " +
        "but this should not happen...");
  }


//  /**
//   * TODO: not used
//   * NOTE:<p>
//   *   in batch, the requested pages will be put into the corresponding
//   *   batchRequestedPages area.
//   *
//   */
//  private static final void gp_Page_pop_batch(long tid,
//                                              int numOfRequestedPages) {
//    long tlpBatchRequestedPage = addressTLPs+ TLP_ITEM_SIZE *tid
//        + TLP_BATCHREQUESTEDPAGES_OFFSET;
//
//    long page = UNSAFE.getAddress(addressGPHead_AvailablePagesHead);
//    for (int i = 0; i < numOfRequestedPages; i++) {
//      if (page==NULL_ADDRESS)
//        break;
//      UNSAFE.putAddress(tlpBatchRequestedPage + i * SIZE_LONG_TYPE, page);
//      page = UNSAFE.getAddress(page);
//    }
//    UNSAFE.putAddress(addressGPHead_AvailablePagesHead,page);
//
//    long newNum = UNSAFE.getLong(addressGPHead_NumAvailablePages)-
//        numOfRequestedPages;
//    UNSAFE.putLong(addressGPHead_NumAvailablePages, (newNum>0)?newNum:0);
//  }

  //============================================================================
  //TLP operations

  private static final void tlp_ini(long tid) {
    long addressTLP     = addressTLPs + TLP_ITEM_SIZE *tid;
    long availablePages = addressTLP;

    for (int i = 0; i < TLP_AVAILABLEPAGES_ARRAYLENGTH; i++) {
      long availablePageHead = availablePages + i* TLP_AVAILABLEPAGES_ITEM_SIZE;
      long availablePageTail = availablePageHead + SIZE_LONG_TYPE;

      //reset the available pages array
      UNSAFE.putAddress(availablePageHead,availablePageTail);
      UNSAFE.putAddress(availablePageTail,availablePageHead);//TODO
      UNSAFE.putLong(availablePageTail+SIZE_LONG_TYPE, 0);
      //XXX: not necessary for numAvailablePage
    }
    long addrFreePagesHead = addressTLP + TLP_FREEPAGESHEAD_OFFSET;
    long addrFreePagesTail = addressTLP + TLP_FREEPAGESTAIL_OFFSET;
    UNSAFE.putAddress(addrFreePagesHead,addrFreePagesTail);
    UNSAFE.putAddress(addrFreePagesTail,addrFreePagesHead);
    //XXX: not necessary for numFreePages

    long addrRemoteFreedChunksHead =
        addressTLP + TLP_REMOTEFREEDCHUNKS_HEAD_OFFSET;
    long addrRemoteFreedChunksTail =
        addressTLP + TLP_REMOTEFREEDCHUNKS_TAIL_OFFSET;
    long addrRemoteFreedChunksDummy =
        addressTLP + TLP_REMOTEFREEDCHUNKS_DUMMY_OFFSET;
    UNSAFE.putAddress(addrRemoteFreedChunksDummy,NULL_ADDRESS);//not necessary
    UNSAFE.putAddress(addrRemoteFreedChunksHead,addrRemoteFreedChunksDummy);
    UNSAFE.putAddress(addrRemoteFreedChunksTail,addrRemoteFreedChunksDummy);

    UNSAFE.putByte(addressTLPStates + tid, (byte) 1);//set TLPStates
    UNSAFE.getAndAddInt(null, addressTLPHEAD, 1);
  }

  private static final void tlp_AvailablePages_addToHead(
      long addrAvailablePageHead,
      long newAvailablePage) {
    long addrNumAvailablePage = addrAvailablePageHead+2*SIZE_LONG_TYPE;
    long numAvailablePage = UNSAFE.getLong(addrNumAvailablePage);

    long oldHead = UNSAFE.getAddress(addrAvailablePageHead);

    if (numAvailablePage != 0) {//oldHead!=availablePageTail
      UNSAFE.putAddress(oldHead + ZMPAGE_PREVPAGE_OFFSET,
          newAvailablePage);
    }else {//oldHead is tail
      UNSAFE.putAddress(oldHead, newAvailablePage);
    }
    UNSAFE.putAddress(newAvailablePage + ZMPAGE_NEXTPAGE_OFFSET, oldHead);
    UNSAFE.putAddress(newAvailablePage + ZMPAGE_PREVPAGE_OFFSET,
        addrAvailablePageHead);
    UNSAFE.putAddress(addrAvailablePageHead, newAvailablePage);

    UNSAFE.putLong(addrNumAvailablePage, numAvailablePage + 1);
  }

  private static final void tlp_AvailablePages_addToTail(
      long tid,
      int sizeClassIndex,
      long newAvailablePage) {

    long addrAvailablePages = addressTLPs + TLP_ITEM_SIZE *tid;
//    long availablePageHead = availablePages +
//        sizeClassIndex*TLP_AVAILABLEPAGES_ITEM_SIZE;
    long addrAvailablePageTail = addrAvailablePages +
        sizeClassIndex* TLP_AVAILABLEPAGES_ITEM_SIZE +
        SIZE_LONG_TYPE;
    long addrNumAvailablePage = addrAvailablePageTail+SIZE_LONG_TYPE;
    long numAvailablePage = UNSAFE.getLong(addrNumAvailablePage);

    long oldTail = UNSAFE.getAddress(addrAvailablePageTail);
    if (numAvailablePage != 0) {//oldTail is not addrAvailablePageHead
      UNSAFE.putAddress(oldTail + ZMPAGE_NEXTPAGE_OFFSET,
          newAvailablePage);
    } else {//oldTail is head
      UNSAFE.putAddress(oldTail,newAvailablePage);
    }
    UNSAFE.putAddress(newAvailablePage+ZMPAGE_PREVPAGE_OFFSET,oldTail);
    UNSAFE.putAddress(newAvailablePage+ZMPAGE_NEXTPAGE_OFFSET,
        addrAvailablePageTail);
    UNSAFE.putAddress(addrAvailablePageTail,newAvailablePage);

    UNSAFE.putLong(addrNumAvailablePage, numAvailablePage+1);
  }

  //TODO: some state changes may be not necessary?
  /**
   * contract:
   * <p>  1. numAvailablePage > 0
   * <p>  2. removedPage in AvailablePages[sizeClassIndex]
   */
  private static final void tlp_AvailablePages_remove(
      long addrAvailablePageHead,
      long removedPage) {
    long availablePageTail = addrAvailablePageHead + SIZE_LONG_TYPE;

//    long head = UNSAFE.getAddress();
//    long tail = UNSAFE.getAddress();
    long addrPrevPage = removedPage+ZMPAGE_PREVPAGE_OFFSET;
    long addrNextPage = removedPage+ZMPAGE_NEXTPAGE_OFFSET;
    long prev = UNSAFE.getAddress(addrPrevPage);
    long next = UNSAFE.getAddress(addrNextPage);

    if (prev!=addrAvailablePageHead) {
      UNSAFE.putAddress(prev+ZMPAGE_NEXTPAGE_OFFSET,next);
    } else {//prev is head
      UNSAFE.putAddress(prev,next);
    }
    if (next!=availablePageTail) {
      UNSAFE.putAddress(next+ZMPAGE_PREVPAGE_OFFSET,prev);
    } else {//next is tail
      UNSAFE.putAddress(next,prev);
    }

    long addrNumAvailablePage = availablePageTail+SIZE_LONG_TYPE;

    UNSAFE.putLong(addrNumAvailablePage,
        UNSAFE.getLong(addrNumAvailablePage)-1);
  }

  //============================
  private static final void tlp_FreePages_addToHead(long addrFreePagesHead,
                                                    long newFreePage,
                                                    long addrNumFreePages) {
    long numFreePages = UNSAFE.getLong(addrNumFreePages);

    long oldHead = UNSAFE.getAddress(addrFreePagesHead);

    if (numFreePages != 0) {//oldHead!=availablePageTail
      UNSAFE.putAddress(oldHead + ZMPAGE_PREVFREEPAGE_OFFSET, newFreePage);
    }else {//oldHead is tail
      UNSAFE.putAddress(oldHead, newFreePage);
    }
    UNSAFE.putAddress(newFreePage + ZMPAGE_NEXTFREEPAGE_OFFSET, oldHead);
    UNSAFE.putAddress(newFreePage + ZMPAGE_PREVFREEPAGE_OFFSET,
        addrFreePagesHead);
    UNSAFE.putAddress(addrFreePagesHead, newFreePage);

    UNSAFE.putLong(addrNumFreePages, numFreePages + 1);
  }

  private static final void tlp_FreePages_remove(long addrFreePagesHead,
                                                 long addrFreePagesTail,
                                                 long addrNumFreePages,
                                                 long removedFreePage) {
    long addrPrevFreePage = removedFreePage+ZMPAGE_PREVFREEPAGE_OFFSET;
    long addrNextFreePage = removedFreePage+ZMPAGE_NEXTFREEPAGE_OFFSET;
    long prev = UNSAFE.getAddress(addrPrevFreePage);
    long next = UNSAFE.getAddress(addrNextFreePage);

    if (prev!=addrFreePagesHead) {
      UNSAFE.putAddress(prev+ZMPAGE_NEXTFREEPAGE_OFFSET,next);
    } else {//prev is head
      UNSAFE.putAddress(prev,next);
    }
    if (next!=addrFreePagesTail) {
      UNSAFE.putAddress(next+ZMPAGE_PREVFREEPAGE_OFFSET,prev);
    } else {//next is tail
      UNSAFE.putAddress(next,prev);
    }

    UNSAFE.putLong(addrNumFreePages, UNSAFE.getLong(addrNumFreePages)-1);
  }

  //============================================================================
  private static final void tlp_RemoteFreedChunksHead_add(
      long addrRemoteFreedChunksTail,
      long addrRemoteFreedChunk) {
    UNSAFE.putAddress(addrRemoteFreedChunk,NULL_ADDRESS);
//    UNSAFE.fullFence();
    long oldTail = UNSAFE.getAndSetLong(null,
        addrRemoteFreedChunksTail, addrRemoteFreedChunk);
    UNSAFE.putAddress(oldTail, addrRemoteFreedChunk);
    UNSAFE.fullFence();
  }

  /*
   * TODO: here we have no ABA problem: because we have a single consumer here,
   * it is not possible to change the head without this method itself.
   */
  private static final long tlp_RemoteFreedChunksHead_remove(
      long addrRemoteFreedChunksHead,
      long addrRemoteFreedChunksTail,
      long addrRemoteFreedChunksDummy) {
    UNSAFE.loadFence();
    long head = UNSAFE.getAddress(addrRemoteFreedChunksHead);
    long v    = UNSAFE.getAddress(head);
    if (v!=NULL_ADDRESS) {
      long next = UNSAFE.getAddress(v);
      if (next==NULL_ADDRESS) {//v==tail(==getAddr(addrRemoteFreedChunksTail))
        if ( UNSAFE.compareAndSwapLong(null,
            addrRemoteFreedChunksTail,
            v,
            addrRemoteFreedChunksDummy) ) {
          return v;
        } else {
          return NULL_ADDRESS;
        }
      } else {
        UNSAFE.putAddress(head, next);
      }
      UNSAFE.fullFence();//
      return v;
    } else {
      return NULL_ADDRESS;
    }
  }
  //============================================================================
  //Stats API
  /**
   * ManagedPoolStats provides statistics for the managed off-heap pool
   *
   */
  public static class ManagedPoolStats {


    public static long totalManagedPoolSize() {
      return sizeGP;
    }

    public static long currentNumOfGPAvaiablePages() {
      return UNSAFE.getLongVolatile(null, addressGPHead_NumAvailablePages);
    }

    public static long currentNumOfTLPAvaiablePages(int sizeClassIndex) {
      return currentNumOfTLPAvaiablePages(currentThreadId(), sizeClassIndex);
    }

    public static long currentNumOfTLPAvaiablePages(long tid,
                                                    int sci) {
      long addressTLP = addressTLPs + TLP_ITEM_SIZE *tid;
      long addrAvailablePages = addressTLP + TLP_AVAILABLEPAGES_OFFSET;
      long addrAvailablePageHead = addrAvailablePages +
          sci* TLP_AVAILABLEPAGES_ITEM_SIZE;
      long addrNumAvailablePage = addrAvailablePageHead+2*SIZE_LONG_TYPE;
      return UNSAFE.getLongVolatile(null, addrNumAvailablePage);
    }

    public static long currentNumOfTLPFreePages() {
      return currentNumOfTLPFreePages(currentThreadId());
    }

    public static long currentNumOfTLPFreePages(long tid) {
      long numFreePages = addressTLPs + TLP_ITEM_SIZE *tid + TLP_NUMFREEPAGES_OFFSET;
      return UNSAFE.getLong(numFreePages);
    }

    public static int currentNumOfPageAvailableChunks(long addressPage) {
      return UNSAFE.getInt(addressPage+ZMPAGE_NUMAVAILABLECHUNKS_OFFSET);
    }

    public static long currentNumOfTLPs() {
      return UNSAFE.getIntVolatile(null, addressTLPHEAD);//XXX: TLPHEAD_NUMTLPS_OFFSET=0
    }

    public static final long queryAllocationSize(int sizeOfBytes) {
      int sci = sizeClassIndex((int) sizeOfBytes);
      return UNSAFE.getInt(addressSizeClassInfo+sci*SIZE_INT_TYPE);
    }

  }

}
