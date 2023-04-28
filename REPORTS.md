# Test Reports by Lincheck

These test reports describe the bugs detected by Lincheck.

## `ConcurrentLinkedDeque` in Java Standard Library

```agsl
org.jetbrains.kotlinx.lincheck.LincheckAssertionError: 
= Invalid execution results =
Init part:
[addLast(4): void]
Parallel part:
| pollFirst(): 4 | addFirst(-4): void  |
|                | peekLast():   4     |


= The following interleaving leads to the error =
Parallel part trace:
| pollFirst()                                                                                               |                      |
|   pollFirst(): 4 at ConcurrentLinkedDequeTest.pollFirst(ConcurrentLinkedDequeTest.kt:17)                  |                      |
|     first(): Node@1 at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:915)                    |                      |
|     item.READ: null at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:917)                    |                      |
|     next.READ: Node@2 at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:925)                  |                      |
|     item.READ: 4 at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:917)                       |                      |
|     prev.READ: null at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:919)                    |                      |
|     switch                                                                                                |                      |
|                                                                                                           | addFirst(-4): void   |
|                                                                                                           | peekLast(): 4        |
|                                                                                                           |   thread is finished |
|     compareAndSet(Node@2,4,null): true at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:920) |                      |
|     unlink(Node@2) at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:921)                     |                      |
|   result: 4                                                                                               |                      |
|   thread is finished                                                                                      |                      |
```

## `AbstractQueueSynchronizer` / `Semaphore` in Java Standard Library
```agsl
This test has failed with an unexpected error: 
 = Invalid execution results =
Parallel part:
| release(): void [-,-,2] | acquire():          S [-,-,2] | acquire() + cancel: void                          |
|                         | acquire() + cancel: - [1,1,2] | acquire() + cancel: SUSPENDED + CANCELLED [-,-,1] |
---
values in "[..]" brackets indicate the number of completed operations 
in each of the parallel threads seen at the beginning of the current operation
---

= The following interleaving leads to the error =
Parallel part trace:
|                      |                                                                                                                                                                  | acquire() + cancel: void                  |
|                      |                                                                                                                                                                  | acquire() + cancel: SUSPENDED + CANCELLED |
|                      |                                                                                                                                                                  |   thread is finished                      |
|                      | acquire()                                                                                                                                                        |                                           |
|                      |   acquire(): threw ParkedThreadFinish at SemaphoreTest.acquire(SemaphoreTest.kt:32)                                                                              |                                           |
|                      |     acquireSharedInterruptibly(1): threw ParkedThreadFinish at Semaphore.acquire(Semaphore.java:318)                                                             |                                           |
|                      |       tryAcquireShared(1): -1 at AbstractQueuedSynchronizer.acquireSharedInterruptibly(AbstractQueuedSynchronizer.java:1046)                                     |                                           |
|                      |       acquire(null,1,true,true,false,0): threw ParkedThreadFinish at AbstractQueuedSynchronizer.acquireSharedInterruptibly(AbstractQueuedSynchronizer.java:1047) |                                           |
|                      |         tryAcquireShared(1): -1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:670)                                                       |                                           |
|                      |         tryAcquireShared(1): -1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:670)                                                       |                                           |
|                      |         tail.READ: SharedNode@1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:698)                                                       |                                           |
|                      |         setPrevRelaxed(SharedNode@1) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:699)                                                  |                                           |
|                      |         casTail(SharedNode@1,SharedNode@2): true at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:702)                                      |                                           |
|                      |         next.WRITE(SharedNode@2) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:705)                                                      |                                           |
|                      |         prev.READ: SharedNode@1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:656)                                                       |                                           |
|                      |         head.READ: ExclusiveNode@1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:656)                                                    |                                           |
|                      |         status.READ: 1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:658)                                                                |                                           |
|                      |         prev.READ: ExclusiveNode@1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:661)                                                    |                                           |
|                      |         status.READ: 0 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:709)                                                                |                                           |
|                      |         status.WRITE(1) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:710)                                                               |                                           |
|                      |         prev.READ: SharedNode@1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:656)                                                       |                                           |
|                      |         head.READ: ExclusiveNode@1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:656)                                                    |                                           |
|                      |         status.READ: 1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:658)                                                                |                                           |
|                      |         prev.READ: ExclusiveNode@1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:661)                                                    |                                           |
|                      |         status.READ: 1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:709)                                                                |                                           |
|                      |         park(FairSync@1): threw ParkedThreadFinish at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:715)                                    |                                           |
|                      |           setBlocker(TestThread@2,FairSync@1) at LockSupport.park(LockSupport.java:210)                                                                          |                                           |
|                      |           switch (reason: coroutine is suspended)                                                                                                                |                                           |
|                      |   result: S                                                                                                                                                      |                                           |
| release(): void      |                                                                                                                                                                  |                                           |
|   thread is finished |                                                                                                                                                                  |                                           |
|                      | acquire() + cancel: -                                                                                                                                            |                                           |
|                      |   thread is finished                                                                                                                                             |                                           |
```

## `NonBlockingHashMapLong` in JCTools
###

```agsl
org.jetbrains.kotlinx.lincheck.LincheckAssertionError: 
= Invalid execution results =
Init part:
[putIfAbsent(2, 6): null]
Parallel part:
| remove(2): 8 | replace(2, 8): 6 |
Post part:
[get(2): 8]

= The following interleaving leads to the error =
Parallel part trace:
| remove(2)                                                                                                              |                      |
|   remove(2): 8 at AbstractConcurrentMapTest.remove(AbstractConcurrentMapTest.kt:33)                                    |                      |
|     remove(2): 8 at NonBlockingHashMapLong.remove(NonBlockingHashMapLong.java:367)                                     |                      |
|       putIfMatch(2,Object@1,Object@2): 8 at NonBlockingHashMapLong.remove(NonBlockingHashMapLong.java:284)             |                      |
|         _chm.READ: CHM@1 at NonBlockingHashMapLong.putIfMatch(NonBlockingHashMapLong.java:316)                         |                      |
|         access$100(CHM@1,2,Object@1,Object@2): 8 at NonBlockingHashMapLong.putIfMatch(NonBlockingHashMapLong.java:316) |                      |
|           putIfMatch(2,Object@1,Object@2): 8 at NonBlockingHashMapLong$CHM.access$100(NonBlockingHashMapLong.java:400) |                      |
|             READ: 6 at NonBlockingHashMapLong$CHM.putIfMatch(NonBlockingHashMapLong.java:559)                          |                      |
|             READ: 2 at NonBlockingHashMapLong$CHM.putIfMatch(NonBlockingHashMapLong.java:560)                          |                      |
|             switch                                                                                                     |                      |
|                                                                                                                        | replace(2, 8): 6     |
|                                                                                                                        |   thread is finished |
|             CAS_val(2,6,Object@1): false at NonBlockingHashMapLong$CHM.putIfMatch(NonBlockingHashMapLong.java:641)     |                      |
|             READ: 8 at NonBlockingHashMapLong$CHM.putIfMatch(NonBlockingHashMapLong.java:651)                          |                      |
|   result: 8                                                                                                            |                      |
|   thread is finished                                                                                                   |                      |
```

## `SnapTree` by Bronson et al.

```agsl
org.jetbrains.kotlinx.lincheck.LincheckAssertionError: 
= The execution failed with an unexpected exception =
Execution scenario (init part):
[getOrPut(4, 4)]
Execution scenario (parallel part):
| firstKey() | getOrPut(1, 7) | getOrPut(5, 6) |
|            | remove(4)      |                |

java.lang.AssertionError
	at SnapTree.SnapTreeMap.attemptExtreme(SnapTreeMap.java:703)
	at SnapTree.SnapTreeMap.extreme(SnapTreeMap.java:676)
	at SnapTree.SnapTreeMap.extremeKeyOrThrow(SnapTreeMap.java:654)
	at SnapTree.SnapTreeMap.firstKey(SnapTreeMap.java:634)
	at SnapTreeTest.firstKey(SnapTreeTest.kt:11)
	at org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution1794.run(Unknown Source)
	at org.jetbrains.kotlinx.lincheck.runner.FixedActiveThreadsExecutor$testThreadRunnable$1.run(FixedActiveThreadsExecutor.kt:173)
	at java.base/java.lang.Thread.run(Thread.java:1589)


= The following interleaving leads to the error =
Parallel part trace:
| firstKey()                                                                                                 |                      |                      |
|   firstKey(): threw AssertionError at SnapTreeTest.firstKey(SnapTreeTest.kt:11)                            |                      |                      |
|     extremeKeyOrThrow(L): threw AssertionError at SnapTreeMap.firstKey(SnapTreeMap.java:634)               |                      |                      |
|       extreme(true,L): threw AssertionError at SnapTreeMap.extremeKeyOrThrow(SnapTreeMap.java:654)         |                      |                      |
|         holderRef.READ: COWMgr@1 at SnapTreeMap.extreme(SnapTreeMap.java:666)                              |                      |                      |
|         read(): RootHolder@1 at SnapTreeMap.extreme(SnapTreeMap.java:666)                                  |                      |                      |
|         right.READ: Node@1 at SnapTreeMap.extreme(SnapTreeMap.java:666)                                    |                      |                      |
|         shrinkOVL.READ: 0 at SnapTreeMap.extreme(SnapTreeMap.java:670)                                     |                      |                      |
|         holderRef.READ: COWMgr@1 at SnapTreeMap.extreme(SnapTreeMap.java:674)                              |                      |                      |
|         read(): RootHolder@1 at SnapTreeMap.extreme(SnapTreeMap.java:674)                                  |                      |                      |
|         right.READ: Node@1 at SnapTreeMap.extreme(SnapTreeMap.java:674)                                    |                      |                      |
|         attemptExtreme(true,L,Node@1,0): threw AssertionError at SnapTreeMap.extreme(SnapTreeMap.java:676) |                      |                      |
|           child(L): null at SnapTreeMap.attemptExtreme(SnapTreeMap.java:691)                               |                      |                      |
|           switch                                                                                           |                      |                      |
|                                                                                                            |                      | getOrPut(5, 6)       |
|                                                                                                            |                      |   thread is finished |
|                                                                                                            | getOrPut(1, 7)       |                      |
|                                                                                                            | remove(4)            |                      |
|                                                                                                            |   thread is finished |                      |
|           vOpt.READ: null at SnapTreeMap.attemptExtreme(SnapTreeMap.java:697)                              |                      |                      |
|           shrinkOVL.READ: 0 at SnapTreeMap.attemptExtreme(SnapTreeMap.java:699)                            |                      |                      |
```

## `LogicalOrderingAVL` by Drachsler et al.
```agsl
org.jetbrains.kotlinx.lincheck.LincheckAssertionError: 
= The execution has hung, see the thread dump =
Execution scenario (init part):
[getOrPut(1, 4)]
Execution scenario (parallel part):
| put(2, 6) | putIfAbsent(5, 6) |
|           | remove(2)         |

= The following interleaving leads to the error =
Parallel part trace:
|                                                                                                                                               | putIfAbsent(5, 6)                                                                                                                               |
|                                                                                                                                               |   putIfAbsent(5,6): null at IntIntAbstractConcurrentMapTest.putIfAbsent(AbstractConcurrentMapTest.kt:92)                                        |
|                                                                                                                                               |     insert(5,6,true,false,null): null at LogicalOrderingAVL.putIfAbsent(LogicalOrderingAVL.java:205)                                            |
|                                                                                                                                               |       comparable(5): 5 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:243)                                                                |
|                                                                                                                                               |       root.READ: AVLMapNode@1 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:248)                                                         |
|                                                                                                                                               |       left.READ: AVLMapNode@2 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:256)                                                         |
|                                                                                                                                               |       right.READ: null at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:254)                                                                |
|                                                                                                                                               |       lockSuccLock() at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:264)                                                                  |
|                                                                                                                                               |       valid.READ: true at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:265)                                                                |
|                                                                                                                                               |       succ.READ: AVLMapNode@1 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:269)                                                         |
|                                                                                                                                               |       chooseParent(AVLMapNode@2,AVLMapNode@1,AVLMapNode@2): AVLMapNode@2 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:286)              |
|                                                                                                                                               |       pred.WRITE(AVLMapNode@3) at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:288)                                                        |
|                                                                                                                                               |       succ.WRITE(AVLMapNode@3) at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:289)                                                        |
|                                                                                                                                               |       unlockSuccLock() at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:290)                                                                |
|                                                                                                                                               |       insertToTree(AVLMapNode@2,AVLMapNode@3,true) at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:291)                                    |
|                                                                                                                                               |         right.WRITE(AVLMapNode@3) at LogicalOrderingAVL.insertToTree(LogicalOrderingAVL.java:341)                                               |
|                                                                                                                                               |         rightHeight.WRITE(1) at LogicalOrderingAVL.insertToTree(LogicalOrderingAVL.java:342)                                                    |
|                                                                                                                                               |         root.READ: AVLMapNode@1 at LogicalOrderingAVL.insertToTree(LogicalOrderingAVL.java:347)                                                 |
|                                                                                                                                               |         lockParent(AVLMapNode@2): AVLMapNode@1 at LogicalOrderingAVL.insertToTree(LogicalOrderingAVL.java:348)                                  |
|                                                                                                                                               |           parent.READ: AVLMapNode@1 at LogicalOrderingAVL.lockParent(LogicalOrderingAVL.java:365)                                               |
|                                                                                                                                               |           lockTreeLock() at LogicalOrderingAVL.lockParent(LogicalOrderingAVL.java:366)                                                          |
|                                                                                                                                               |           parent.READ: AVLMapNode@1 at LogicalOrderingAVL.lockParent(LogicalOrderingAVL.java:367)                                               |
|                                                                                                                                               |           switch                                                                                                                                |
| put(2, 6)                                                                                                                                     |                                                                                                                                                 |
|   put(2,6) at IntIntAbstractConcurrentMapTest.put(AbstractConcurrentMapTest.kt:67)                                                            |                                                                                                                                                 |
|     insert(2,6,false,false,null) at LogicalOrderingAVL.put(LogicalOrderingAVL.java:197)                                                       |                                                                                                                                                 |
|       comparable(2): 2 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:243)                                                              |                                                                                                                                                 |
|       root.READ: AVLMapNode@1 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:248)                                                       |                                                                                                                                                 |
|       left.READ: AVLMapNode@2 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:256)                                                       |                                                                                                                                                 |
|       right.READ: AVLMapNode@3 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:254)                                                      |                                                                                                                                                 |
|       left.READ: null at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:256)                                                               |                                                                                                                                                 |
|       pred.READ: AVLMapNode@2 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:263)                                                       |                                                                                                                                                 |
|       lockSuccLock() at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:264)                                                                |                                                                                                                                                 |
|       valid.READ: true at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:265)                                                              |                                                                                                                                                 |
|       succ.READ: AVLMapNode@3 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:269)                                                       |                                                                                                                                                 |
|       chooseParent(AVLMapNode@2,AVLMapNode@3,AVLMapNode@3): AVLMapNode@3 at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:286)            |                                                                                                                                                 |
|       pred.WRITE(AVLMapNode@4) at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:288)                                                      |                                                                                                                                                 |
|       succ.WRITE(AVLMapNode@4) at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:289)                                                      |                                                                                                                                                 |
|       unlockSuccLock() at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:290)                                                              |                                                                                                                                                 |
|       insertToTree(AVLMapNode@3,AVLMapNode@4,false) at LogicalOrderingAVL.insert(LogicalOrderingAVL.java:291)                                 |                                                                                                                                                 |
|         left.WRITE(AVLMapNode@4) at LogicalOrderingAVL.insertToTree(LogicalOrderingAVL.java:344)                                              |                                                                                                                                                 |
|         leftHeight.WRITE(1) at LogicalOrderingAVL.insertToTree(LogicalOrderingAVL.java:345)                                                   |                                                                                                                                                 |
|         root.READ: AVLMapNode@1 at LogicalOrderingAVL.insertToTree(LogicalOrderingAVL.java:347)                                               |                                                                                                                                                 |
|         lockParent(AVLMapNode@3): AVLMapNode@2 at LogicalOrderingAVL.insertToTree(LogicalOrderingAVL.java:348)                                |                                                                                                                                                 |
|           parent.READ: AVLMapNode@2 at LogicalOrderingAVL.lockParent(LogicalOrderingAVL.java:365)                                             |                                                                                                                                                 |
|           lockTreeLock() at LogicalOrderingAVL.lockParent(LogicalOrderingAVL.java:366)                                                        |                                                                                                                                                 |
|             lock() at LogicalOrderingAVL$AVLMapNode.lockTreeLock(LogicalOrderingAVL.java:1030)                                                |                                                                                                                                                 |
|               lock() at ReentrantLock.lock(ReentrantLock.java:322)                                                                            |                                                                                                                                                 |
|                 initialTryLock(): false at ReentrantLock$Sync.lock(ReentrantLock.java:152)                                                    |                                                                                                                                                 |
|                 acquire(1) at ReentrantLock$Sync.lock(ReentrantLock.java:153)                                                                 |                                                                                                                                                 |
|                   tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:937)                             |                                                                                                                                                 |
|                   acquire(null,1,false,false,false,0): 1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:938)           |                                                                                                                                                 |
|                     tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                           |                                                                                                                                                 |
|                     tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                           |                                                                                                                                                 |
|                     tail.READ: null at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:698)                                |                                                                                                                                                 |
|                     setPrevRelaxed(null) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:699)                           |                                                                                                                                                 |
|                     tryInitializeHead() at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:701)                            |                                                                                                                                                 |
|                     tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                           |                                                                                                                                                 |
|                     tail.READ: ExclusiveNode@1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:698)                     |                                                                                                                                                 |
|                     setPrevRelaxed(ExclusiveNode@1) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:699)                |                                                                                                                                                 |
|                     casTail(ExclusiveNode@1,ExclusiveNode@2): true at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:702) |                                                                                                                                                 |
|                     next.WRITE(ExclusiveNode@2) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:705)                    |                                                                                                                                                 |
|                     prev.READ: ExclusiveNode@1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:656)                     |                                                                                                                                                 |
|                     head.READ: ExclusiveNode@1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:656)                     |                                                                                                                                                 |
|                     tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                           |                                                                                                                                                 |
|                     status.READ: 0 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:709)                                 |                                                                                                                                                 |
|                     status.WRITE(1) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:710)                                |                                                                                                                                                 |
|                     tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                           |                                                                                                                                                 |
|                     status.READ: 1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:709)                                 |                                                                                                                                                 |
|                     park(NonfairSync@1) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:715)                            |                                                                                                                                                 |
|                     clearStatus() at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:720)                                  |                                                                                                                                                 |
|                     tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                           |                                                                                                                                                 |
|                     tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                           |                                                                                                                                                 |
|                     status.READ: 0 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:709)                                 |                                                                                                                                                 |
|                     status.WRITE(1) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:710)                                |                                                                                                                                                 |
|                     tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                           |                                                                                                                                                 |
|                     status.READ: 1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:709)                                 |                                                                                                                                                 |
|                     park(NonfairSync@1) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:715)                            |                                                                                                                                                 |
|                     clearStatus() at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:720)                                  |                                                                                                                                                 |
|                     tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                           |                                                                                                                                                 |
|                     tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                           |                                                                                                                                                 |
|                     (...)                                                                                                                     |                                                                                                                                                 |
|                     switch (reason: active lock detected)                                                                                     |                                                                                                                                                 |
|                                                                                                                                               |           valid.READ: true at LogicalOrderingAVL.lockParent(LogicalOrderingAVL.java:367)                                                        |
|                                                                                                                                               |         left.READ: AVLMapNode@2 at LogicalOrderingAVL.insertToTree(LogicalOrderingAVL.java:349)                                                 |
|                                                                                                                                               |         rebalance(AVLMapNode@1,AVLMapNode@2,true) at LogicalOrderingAVL.insertToTree(LogicalOrderingAVL.java:349)                               |
|                                                                                                                                               | remove(2)                                                                                                                                       |
|                                                                                                                                               |   remove(2) at IntIntAbstractConcurrentMapTest.remove(AbstractConcurrentMapTest.kt:83)                                                          |
|                                                                                                                                               |     remove(2,false,null) at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:384)                                                              |
|                                                                                                                                               |       comparable(2): 2 at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:408)                                                                |
|                                                                                                                                               |       root.READ: AVLMapNode@1 at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:413)                                                         |
|                                                                                                                                               |       left.READ: AVLMapNode@2 at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:421)                                                         |
|                                                                                                                                               |       right.READ: AVLMapNode@3 at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:419)                                                        |
|                                                                                                                                               |       left.READ: AVLMapNode@4 at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:421)                                                         |
|                                                                                                                                               |       pred.READ: AVLMapNode@2 at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:428)                                                         |
|                                                                                                                                               |       lockSuccLock() at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:429)                                                                  |
|                                                                                                                                               |       valid.READ: true at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:430)                                                                |
|                                                                                                                                               |       succ.READ: AVLMapNode@4 at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:434)                                                         |
|                                                                                                                                               |       lockSuccLock() at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:442)                                                                  |
|                                                                                                                                               |       acquireTreeLocks(AVLMapNode@4): null at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:443)                                            |
|                                                                                                                                               |       lockParent(AVLMapNode@4): AVLMapNode@3 at LogicalOrderingAVL.remove(LogicalOrderingAVL.java:444)                                          |
|                                                                                                                                               |         parent.READ: AVLMapNode@3 at LogicalOrderingAVL.lockParent(LogicalOrderingAVL.java:365)                                                 |
|                                                                                                                                               |         lockTreeLock() at LogicalOrderingAVL.lockParent(LogicalOrderingAVL.java:366)                                                            |
|                                                                                                                                               |           lock() at LogicalOrderingAVL$AVLMapNode.lockTreeLock(LogicalOrderingAVL.java:1030)                                                    |
|                                                                                                                                               |             lock() at ReentrantLock.lock(ReentrantLock.java:322)                                                                                |
|                                                                                                                                               |               initialTryLock(): false at ReentrantLock$Sync.lock(ReentrantLock.java:152)                                                        |
|                                                                                                                                               |               acquire(1) at ReentrantLock$Sync.lock(ReentrantLock.java:153)                                                                     |
|                                                                                                                                               |                 tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:937)                                 |
|                                                                                                                                               |                 acquire(null,1,false,false,false,0): 1 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:938)               |
|                                                                                                                                               |                   tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                               |
|                                                                                                                                               |                   tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                               |
|                                                                                                                                               |                   tail.READ: null at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:698)                                    |
|                                                                                                                                               |                   setPrevRelaxed(null) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:699)                               |
|                                                                                                                                               |                   tryInitializeHead() at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:701)                                |
|                                                                                                                                               |                   tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                               |
|                                                                                                                                               |                   tail.READ: ExclusiveNode@3 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:698)                         |
|                                                                                                                                               |                   setPrevRelaxed(ExclusiveNode@3) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:699)                    |
|                                                                                                                                               |                   casTail(ExclusiveNode@3,ExclusiveNode@4): true at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:702)     |
|                                                                                                                                               |                   next.WRITE(ExclusiveNode@4) at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:705)                        |
|                                                                                                                                               |                   prev.READ: ExclusiveNode@3 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:656)                         |
|                                                                                                                                               |                   head.READ: ExclusiveNode@3 at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:656)                         |
|                                                                                                                                               |                   tryAcquire(1): false at AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:672)                               |
|                                                                                                                                               |                   (endless deadlock)                                                                                                            |
```
## `CATree` by Sagonas and Winblad

```agsl
org.jetbrains.kotlinx.lincheck.LincheckAssertionError: 
= The execution has hung, see the thread dump =
Execution scenario (parallel part):
| clear()           | clear() |
| putIfAbsent(3, 2) |         |

= The following interleaving leads to the error =
Parallel part trace:
|                                                                                                                  | clear()                                                                                                  |
|                                                                                                                  |   clear() at CATreeTest.clear(CATreeTest.kt:38)                                                          |
|                                                                                                                  |     lockAll() at CATreeMapAVL.clear(CATreeMapAVL.java:714)                                               |
|                                                                                                                  |       root.READ: DualLFCASAVLTreeMapSTD@1 at CATreeMapAVL.lockAll(CATreeMapAVL.java:504)                 |
|                                                                                                                  |       switch                                                                                             |
| clear()                                                                                                          |                                                                                                          |
| putIfAbsent(3, 2)                                                                                                |                                                                                                          |
|   putIfAbsent(3,2) at CATreeTest.putIfAbsent(CATreeTest.kt:32)                                                   |                                                                                                          |
|     getBaseNode(3): DualLFCASAVLTreeMapSTD@2 at CATreeMapAVL.putIfAbsent(CATreeMapAVL.java:630)                  |                                                                                                          |
|     switch                                                                                                       |                                                                                                          |
|                                                                                                                  |       lockAllHelper(DualLFCASAVLTreeMapSTD@1,ArrayList@1) at CATreeMapAVL.lockAll(CATreeMapAVL.java:504) |
|                                                                                                                  |     root.READ: DualLFCASAVLTreeMapSTD@2 at CATreeMapAVL.clear(CATreeMapAVL.java:715)                     |
|                                                                                                                  |     root.WRITE(DualLFCASAVLTreeMapSTD@3) at CATreeMapAVL.clear(CATreeMapAVL.java:716)                    |
|                                                                                                                  |     unlockAllHelper(DualLFCASAVLTreeMapSTD@2) at CATreeMapAVL.clear(CATreeMapAVL.java:717)               |
|                                                                                                                  |   thread is finished                                                                                     |
|     getLockFreeMap(): null at CATreeMapAVL.putIfAbsent(CATreeMapAVL.java:634)                                    |                                                                                                          |
|     lockIfNotLockFreeWithKey(3) at CATreeMapAVL.putIfAbsent(CATreeMapAVL.java:653)                               |                                                                                                          |
|       getLockFreeMap(): null at DualLFCASAVLTreeMapSTD.lockIfNotLockFreeWithKey(DualLFCASAVLTreeMapSTD.java:743) |                                                                                                          |
|       tryLock(): false at DualLFCASAVLTreeMapSTD.lockIfNotLockFreeWithKey(DualLFCASAVLTreeMapSTD.java:753)       |                                                                                                          |
|       lock() at DualLFCASAVLTreeMapSTD.lockIfNotLockFreeWithKey(DualLFCASAVLTreeMapSTD.java:766)                 |                                                                                                          |
|         seqNumber.READ: 3 at SeqLock.lock(SeqLock.java:75)                                                       |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:77)                                                             |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:78)                                                             |                                                                                                          |
|         seqNumber.READ: 3 at SeqLock.lock(SeqLock.java:79)                                                       |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:77)                                                             |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:78)                                                             |                                                                                                          |
|         seqNumber.READ: 3 at SeqLock.lock(SeqLock.java:79)                                                       |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:77)                                                             |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:78)                                                             |                                                                                                          |
|         seqNumber.READ: 3 at SeqLock.lock(SeqLock.java:79)                                                       |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:77)                                                             |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:78)                                                             |                                                                                                          |
|         seqNumber.READ: 3 at SeqLock.lock(SeqLock.java:79)                                                       |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:77)                                                             |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:78)                                                             |                                                                                                          |
|         seqNumber.READ: 3 at SeqLock.lock(SeqLock.java:79)                                                       |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:77)                                                             |                                                                                                          |
|         fullFence() at SeqLock.lock(SeqLock.java:78)                                                             |                                                                                                          |
|         (repeats endlessly)                                                                                      |                                                                                                          | 
```


## `ConcurrencyOptimalTree` by Aksenov et al.
```agsl
org.jetbrains.kotlinx.lincheck.LincheckAssertionError: 
= Invalid execution results =
Parallel part:
| putIfAbsent(1, 5): NullPointerException | putIfAbsent(3, 1): null |

= The following interleaving leads to the error =
Parallel part trace:
|                                         | putIfAbsent(3, 1)                                                                                                              |
|                                         |   putIfAbsent(3,1): null at ConcurrencyOptimalMapTest.putIfAbsent(ConcurrencyOptimalMapTest.kt:17)                             |
|                                         |     <init>(ConcurrencyOptimalTreeMap@1) at ConcurrencyOptimalTreeMap.putIfAbsent(ConcurrencyOptimalTreeMap.java:452)           |
|                                         |     traverse(3,Window@1): Window@1 at ConcurrencyOptimalTreeMap.putIfAbsent(ConcurrencyOptimalTreeMap.java:454)                |
|                                         |     validateRefAndTryLock(Node@1,null,true): true at ConcurrencyOptimalTreeMap.putIfAbsent(ConcurrencyOptimalTreeMap.java:478) |
|                                         |     readLockState() at ConcurrencyOptimalTreeMap.putIfAbsent(ConcurrencyOptimalTreeMap.java:479)                               |
|                                         |     deleted.READ: false at ConcurrencyOptimalTreeMap.putIfAbsent(ConcurrencyOptimalTreeMap.java:480)                           |
|                                         |     switch                                                                                                                     |
| putIfAbsent(1, 5): NullPointerException |                                                                                                                                |
|   thread is finished                    |                                                                                                                                |
|                                         |     l.WRITE(Node@2) at ConcurrencyOptimalTreeMap.putIfAbsent(ConcurrencyOptimalTreeMap.java:482)                               |
|                                         |     unlockReadState() at ConcurrencyOptimalTreeMap.putIfAbsent(ConcurrencyOptimalTreeMap.java:486)                             |
|                                         |     undoValidateAndTryLock(Node@1,true) at ConcurrencyOptimalTreeMap.putIfAbsent(ConcurrencyOptimalTreeMap.java:487)           |
|                                         |   result: null                                                                                                                 |
|                                         |   thread is finished                                                                                                           |
```


## `ConcurrentSuffixTree` by Gallagher et al.
```agsl
org.jetbrains.kotlinx.lincheck.LincheckAssertionError: 
= Invalid execution results =
Parallel part:
| put(baa, 5): null | getKeysContaining(baa): [baa] |
|                   | getKeysContaining(aa):  []    |

= The following interleaving leads to the error =
Parallel part trace:
| put(baa, 5)                                                                                                        |                               |
|   put(baa,5): null at ConcurrentSuffixTreeTest.put(ConcurrentSuffixTreeTest.kt:34)                                 |                               |
|     acquireWriteLock() at ConcurrentSuffixTree.put(ConcurrentSuffixTree.java:87)                                   |                               |
|     put(baa,5): null at ConcurrentSuffixTree.put(ConcurrentSuffixTree.java:94)                                     |                               |
|     addSuffixesToRadixTree(baa) at ConcurrentSuffixTree.put(ConcurrentSuffixTree.java:98)                          |                               |
|       getValueForExactKey(baa): null at ConcurrentSuffixTree.addSuffixesToRadixTree(ConcurrentSuffixTree.java:163) |                               |
|       put(baa,SetFromMap@1): null at ConcurrentSuffixTree.addSuffixesToRadixTree(ConcurrentSuffixTree.java:166)    |                               |
|       add(baa): true at ConcurrentSuffixTree.addSuffixesToRadixTree(ConcurrentSuffixTree.java:168)                 |                               |
|       getValueForExactKey(aa): null at ConcurrentSuffixTree.addSuffixesToRadixTree(ConcurrentSuffixTree.java:163)  |                               |
|       switch                                                                                                       |                               |
|                                                                                                                    | getKeysContaining(baa): [baa] |
|                                                                                                                    | getKeysContaining(aa): []     |
|                                                                                                                    |   thread is finished          |
|       put(aa,SetFromMap@2): null at ConcurrentSuffixTree.addSuffixesToRadixTree(ConcurrentSuffixTree.java:166)     |                               |
|       add(baa): true at ConcurrentSuffixTree.addSuffixesToRadixTree(ConcurrentSuffixTree.java:168)                 |                               |
|       getValueForExactKey(a): null at ConcurrentSuffixTree.addSuffixesToRadixTree(ConcurrentSuffixTree.java:163)   |                               |
|       put(a,SetFromMap@3): null at ConcurrentSuffixTree.addSuffixesToRadixTree(ConcurrentSuffixTree.java:166)      |                               |
|       add(baa): true at ConcurrentSuffixTree.addSuffixesToRadixTree(ConcurrentSuffixTree.java:168)                 |                               |
|     releaseWriteLock() at ConcurrentSuffixTree.put(ConcurrentSuffixTree.java:103)                                  |                               |
|   result: null                                                                                                     |                               |
|   thread is finished                                                                                               |                               |
```

## `ConcurrentRadixTree` by Gallagher et al.
```agsl
= Invalid execution results =
Init part:
[put(aaa, 2): null]
Parallel part:
| put(aba, -6):                          null                                 | put(ab, 4): null [1,-] |
| getKeyValuePairsForKeysStartingWith(): [(aa, 5), (aaa, 2), (aba, -6)] [1,-] | put(aa, 5): null [1,1] |

= The following interleaving leads to the error =
Parallel part trace:
| put(aba, -6): null                                                                                                                        |                      |
| getKeyValuePairsForKeysStartingWith()                                                                                                     |                      |
|   getKeyValuePairsForKeysStartingWith(): @1 at ConcurrentRadixTreeTest.getKeyValuePairsForKeysStartingWith(ConcurrentRadixTreeTest.kt:19) |                      |
|   hasNext(): true at ConcurrentRadixTreeTest.getKeyValuePairsForKeysStartingWith(ConcurrentRadixTreeTest.kt:40)                           |                      |
|     READ: 0 at LazyIterator.hasNext(LazyIterator.java:49)                                                                                 |                      |
|     tryToComputeNext(): true at LazyIterator.hasNext(LazyIterator.java:55)                                                                |                      |
|       computeNext(): KeyValuePairImpl@1 at LazyIterator.tryToComputeNext(LazyIterator.java:60)                                            |                      |
|         computeNext(): KeyValuePairImpl@1 at ConcurrentRadixTree$3$1.computeNext(ConcurrentRadixTree.java:658)                            |                      |
|           hasNext(): true at ConcurrentRadixTree$3$1.computeNext(ConcurrentRadixTree.java:664)                                            |                      |
|           next(): NodeKeyPair@1 at ConcurrentRadixTree$3$1.computeNext(ConcurrentRadixTree.java:665)                                      |                      |
|           hasNext(): true at ConcurrentRadixTree$3$1.computeNext(ConcurrentRadixTree.java:664)                                            |                      |
|             READ: 0 at LazyIterator.hasNext(LazyIterator.java:49)                                                                         |                      |
|             tryToComputeNext(): true at LazyIterator.hasNext(LazyIterator.java:55)                                                        |                      |
|               computeNext(): NodeKeyPair@2 at LazyIterator.tryToComputeNext(LazyIterator.java:60)                                         |                      |
|                 computeNext(): NodeKeyPair@2 at ConcurrentRadixTree$4$1.computeNext(ConcurrentRadixTree.java:783)                         |                      |
|                   size(): 2 at ConcurrentRadixTree$4$1.computeNext(ConcurrentRadixTree.java:801)                                          |                      |
|                   get(1): CharArrayNodeLeafWithValue@1 at ConcurrentRadixTree$4$1.computeNext(ConcurrentRadixTree.java:802)               |                      |
|                   switch                                                                                                                  |                      |
|                                                                                                                                           | put(ab, 4): null     |
|                                                                                                                                           | put(aa, 5): null     |
|                                                                                                                                           |   thread is finished |
|                   get(0): CharArrayNodeDefault@1 at ConcurrentRadixTree$4$1.computeNext(ConcurrentRadixTree.java:802)                     |                      |
|           next(): NodeKeyPair@2 at ConcurrentRadixTree$3$1.computeNext(ConcurrentRadixTree.java:665)                                      |                      |
|           hasNext(): true at ConcurrentRadixTree$3$1.computeNext(ConcurrentRadixTree.java:664)                                            |                      |
|           next(): NodeKeyPair@3 at ConcurrentRadixTree$3$1.computeNext(ConcurrentRadixTree.java:665)                                      |                      |
|   next(): KeyValuePairImpl@1 at ConcurrentRadixTreeTest.getKeyValuePairsForKeysStartingWith(ConcurrentRadixTreeTest.kt:40)                |                      |
|   hasNext(): true at ConcurrentRadixTreeTest.getKeyValuePairsForKeysStartingWith(ConcurrentRadixTreeTest.kt:40)                           |                      |
|   next(): KeyValuePairImpl@2 at ConcurrentRadixTreeTest.getKeyValuePairsForKeysStartingWith(ConcurrentRadixTreeTest.kt:40)                |                      |
|   hasNext(): true at ConcurrentRadixTreeTest.getKeyValuePairsForKeysStartingWith(ConcurrentRadixTreeTest.kt:40)                           |                      |
|   next(): KeyValuePairImpl@3 at ConcurrentRadixTreeTest.getKeyValuePairsForKeysStartingWith(ConcurrentRadixTreeTest.kt:40)                |                      |
|   hasNext(): false at ConcurrentRadixTreeTest.getKeyValuePairsForKeysStartingWith(ConcurrentRadixTreeTest.kt:40)                          |                      |
|   result: [(aa, 5), (aaa, 2), (aba, -6)]                                                                                                  |                      |
|   thread is finished                                                                                                                      |                      |
```

## `Mutex` in Kotlin Coroutines

```agsl
= Obstruction-freedom is required but an active lock has been found =
Execution scenario (parallel part):
| lock() + prompt_cancel | lock() + prompt_cancel |
| unlock()               | lock() + prompt_cancel |

= The following interleaving leads to the error =
Parallel part trace:
|                                                                                                                                                  | lock() + prompt_cancel                                                                                                                           |
|                                                                                                                                                  | lock() + prompt_cancel                                                                                                                           |
|                                                                                                                                                  |   lock$default(MutexImpl@1,null,<cont>,1,null): CoroutineSingletons@1 at MutexLincheckTest.lock(MutexTest.kt:14)                                 |
|                                                                                                                                                  |     lock(null): CoroutineSingletons@1 at Mutex$DefaultImpls.lock$default(Mutex.kt:64)                                                            |
|                                                                                                                                                  |       tryLock(null): false at MutexImpl.lock(Mutex.kt:188)                                                                                       |
|                                                                                                                                                  |       lockSuspend(null): CoroutineSingletons@1 at MutexImpl.lock(Mutex.kt:190)                                                                   |
|                                                                                                                                                  |         _state.READ: Empty@1 at MutexImpl.lockSuspend(Mutex.kt:411)                                                                              |
|                                                                                                                                                  |         _state.compareAndSet(Empty@1,LockedQueue@1): true at MutexImpl.lockSuspend(Mutex.kt:199)                                                 |
|                                                                                                                                                  |         _state.READ: LockedQueue@1 at MutexImpl.lockSuspend(Mutex.kt:411)                                                                        |
|                                                                                                                                                  |         owner.READ: <LOCKED> at MutexImpl.lockSuspend(Mutex.kt:211)                                                                              |
|                                                                                                                                                  |         getPrevNode(): LockedQueue@1 at MutexImpl.lockSuspend(Mutex.kt:417)                                                                      |
|                                                                                                                                                  |         tryCondAddNext(LockCont@1,LockedQueue@1,@1): 0 at MutexImpl.lockSuspend(Mutex.kt:418)                                                    |
|                                                                                                                                                  |           _prev.lazySet(LockedQueue@1) at LockFreeLinkedListNode.tryCondAddNext(LockFreeLinkedList.kt:222)                                       |
|                                                                                                                                                  |           _next.lazySet(LockedQueue@1) at LockFreeLinkedListNode.tryCondAddNext(LockFreeLinkedList.kt:223)                                       |
|                                                                                                                                                  |           switch                                                                                                                                 |
| lock() + prompt_cancel                                                                                                                           |                                                                                                                                                  |
|   lock$default(MutexImpl@1,null,<cont>,1,null): CoroutineSingletons@1 at MutexLincheckTest.lock(MutexTest.kt:14)                                 |                                                                                                                                                  |
|     lock(null): CoroutineSingletons@1 at Mutex$DefaultImpls.lock$default(Mutex.kt:64)                                                            |                                                                                                                                                  |
|       tryLock(null): false at MutexImpl.lock(Mutex.kt:188)                                                                                       |                                                                                                                                                  |
|       lockSuspend(null): CoroutineSingletons@1 at MutexImpl.lock(Mutex.kt:190)                                                                   |                                                                                                                                                  |
|         _state.READ: LockedQueue@1 at MutexImpl.lockSuspend(Mutex.kt:411)                                                                        |                                                                                                                                                  |
|         owner.READ: <LOCKED> at MutexImpl.lockSuspend(Mutex.kt:211)                                                                              |                                                                                                                                                  |
|         getPrevNode(): LockedQueue@1 at MutexImpl.lockSuspend(Mutex.kt:417)                                                                      |                                                                                                                                                  |
|         tryCondAddNext(LockCont@2,LockedQueue@1,@2): 1 at MutexImpl.lockSuspend(Mutex.kt:418)                                                    |                                                                                                                                                  |
|           _prev.lazySet(LockedQueue@1) at LockFreeLinkedListNode.tryCondAddNext(LockFreeLinkedList.kt:222)                                       |                                                                                                                                                  |
|           _next.lazySet(LockedQueue@1) at LockFreeLinkedListNode.tryCondAddNext(LockFreeLinkedList.kt:223)                                       |                                                                                                                                                  |
|           _next.compareAndSet(LockedQueue@1,@2): true at LockFreeLinkedListNode.tryCondAddNext(LockFreeLinkedList.kt:225)                        |                                                                                                                                                  |
|           perform(LockedQueue@1): null at LockFreeLinkedListNode.tryCondAddNext(LockFreeLinkedList.kt:227)                                       |                                                                                                                                                  |
|             _consensus.READ: <NO_DECISION> at AtomicOp.perform(Atomic.kt:94)                                                                     |                                                                                                                                                  |
|             prepare(LockedQueue@1): null at AtomicOp.perform(Atomic.kt:96)                                                                       |                                                                                                                                                  |
|             decide(null): null at AtomicOp.perform(Atomic.kt:96)                                                                                 |                                                                                                                                                  |
|             complete(LockedQueue@1,null) at AtomicOp.perform(Atomic.kt:99)                                                                       |                                                                                                                                                  |
|               complete(LockedQueue@1,null) at LockFreeLinkedListNode$CondAddOp.complete(LockFreeLinkedList.kt:69)                                |                                                                                                                                                  |
|                 _next.compareAndSet(@2,LockCont@2): true at LockFreeLinkedListNode$CondAddOp.complete(LockFreeLinkedList.kt:78)                  |                                                                                                                                                  |
|                 oldNext.READ: LockedQueue@1 at LockFreeLinkedListNode$CondAddOp.complete(LockFreeLinkedList.kt:80)                               |                                                                                                                                                  |
|                 access$finishAdd(LockCont@2,LockedQueue@1) at LockFreeLinkedListNode$CondAddOp.complete(LockFreeLinkedList.kt:80)                |                                                                                                                                                  |
|                   finishAdd(LockedQueue@1) at LockFreeLinkedListNode.access$finishAdd(LockFreeLinkedList.kt:59)                                  |                                                                                                                                                  |
|                     _prev.READ: LockedQueue@1 at LockFreeLinkedListNode.finishAdd(LockFreeLinkedList.kt:678)                                     |                                                                                                                                                  |
|                     getNext(): LockedQueue@1 at LockFreeLinkedListNode.finishAdd(LockFreeLinkedList.kt:543)                                      |                                                                                                                                                  |
|                     _prev.compareAndSet(LockedQueue@1,LockCont@2): true at LockFreeLinkedListNode.finishAdd(LockFreeLinkedList.kt:544)           |                                                                                                                                                  |
|                     switch                                                                                                                       |                                                                                                                                                  |
|                                                                                                                                                  |           _next.compareAndSet(LockedQueue@1,@1): false at LockFreeLinkedListNode.tryCondAddNext(LockFreeLinkedList.kt:225)                       |
|                                                                                                                                                  |         getPrevNode(): LockCont@2 at MutexImpl.lockSuspend(Mutex.kt:417)                                                                         |
|                                                                                                                                                  |         tryCondAddNext(LockCont@1,LockedQueue@1,@1): 1 at MutexImpl.lockSuspend(Mutex.kt:418)                                                    |
|                                                                                                                                                  |         removeOnCancellation(<cont>,LockCont@1) at MutexImpl.lockSuspend(Mutex.kt:215)                                                           |
|                                                                                                                                                  |         getResult(): CoroutineSingletons@1 at MutexImpl.lockSuspend(Mutex.kt:421)                                                                |
|                                                                                                                                                  |           trySuspend(): true at CancellableContinuationImpl.getResult(CancellableContinuationImpl.kt:261)                                        |
|                                                                                                                                                  |           parentHandle.READ: null at CancellableContinuationImpl.getResult(CancellableContinuationImpl.kt:271)                                   |
|                                                                                                                                                  |   EXCEPTION WHILE CANCELLATION                                                                                                                   |
|                                                                                                                                                  |   _state.READ: RemoveOnCancel@1 at CancellableContinuationImpl.cancel(CancellableContinuationImpl.kt:599)                                        |
|                                                                                                                                                  |   _state.compareAndSet(RemoveOnCancel@1,CancelledContinuation@1): true at CancellableContinuationImpl.cancel(CancellableContinuationImpl.kt:179) |
|                                                                                                                                                  |   callCancelHandler(RemoveOnCancel@1,Exception@1) at CancellableContinuationImpl.cancel(CancellableContinuationImpl.kt:181)                      |
|                                                                                                                                                  |     invoke(Exception@1) at CancellableContinuationImpl.callCancelHandler(CancellableContinuationImpl.kt:216)                                     |
|                                                                                                                                                  |       remove() at RemoveOnCancel.invoke(CancellableContinuation.kt:381)                                                                          |
|                                                                                                                                                  |         removeOrNext() at LockFreeLinkedListNode.remove(LockFreeLinkedList.kt:241)                                                               |
|                                                                                                                                                  |           getNext(): LockedQueue@1 at LockFreeLinkedListNode.removeOrNext(LockFreeLinkedList.kt:247)                                             |
|                                                                                                                                                  |           removed(): Removed@1 at LockFreeLinkedListNode.removeOrNext(LockFreeLinkedList.kt:250)                                                 |
|                                                                                                                                                  |           _next.compareAndSet(LockedQueue@1,Removed@1): true at LockFreeLinkedListNode.removeOrNext(LockFreeLinkedList.kt:251)                   |
|                                                                                                                                                  |           correctPrev(null) at LockFreeLinkedListNode.removeOrNext(LockFreeLinkedList.kt:253)                                                    |
|                                                                                                                                                  |             _prev.READ: LockCont@1 at LockFreeLinkedListNode.correctPrev(LockFreeLinkedList.kt:569)                                              |
|                                                                                                                                                  |             _next.READ: Removed@1 at LockFreeLinkedListNode.correctPrev(LockFreeLinkedList.kt:573)                                               |
|                                                                                                                                                  |             switch                                                                                                                               |
|                     isRemoved(): false at LockFreeLinkedListNode.finishAdd(LockFreeLinkedList.kt:547)                                            |                                                                                                                                                  |
|         removeOnCancellation(<cont>,LockCont@2) at MutexImpl.lockSuspend(Mutex.kt:215)                                                           |                                                                                                                                                  |
|         getResult(): CoroutineSingletons@1 at MutexImpl.lockSuspend(Mutex.kt:421)                                                                |                                                                                                                                                  |
|           trySuspend(): true at CancellableContinuationImpl.getResult(CancellableContinuationImpl.kt:261)                                        |                                                                                                                                                  |
|           parentHandle.READ: null at CancellableContinuationImpl.getResult(CancellableContinuationImpl.kt:271)                                   |                                                                                                                                                  |
|   CANCELLED BEFORE RESUMPTION                                                                                                                    |                                                                                                                                                  |
|   _state.READ: RemoveOnCancel@2 at CancellableContinuationImpl.cancel(CancellableContinuationImpl.kt:599)                                        |                                                                                                                                                  |
|   _state.compareAndSet(RemoveOnCancel@2,CancelledContinuation@2): true at CancellableContinuationImpl.cancel(CancellableContinuationImpl.kt:179) |                                                                                                                                                  |
|   callCancelHandler(RemoveOnCancel@2,Exception@1) at CancellableContinuationImpl.cancel(CancellableContinuationImpl.kt:181)                      |                                                                                                                                                  |
|   detachChildIfNonResuable() at CancellableContinuationImpl.cancel(CancellableContinuationImpl.kt:183)                                           |                                                                                                                                                  |
|   resumeMode.READ: 1 at CancellableContinuationImpl.cancel(CancellableContinuationImpl.kt:184)                                                   |                                                                                                                                                  |
|   dispatchResume(1) at CancellableContinuationImpl.cancel(CancellableContinuationImpl.kt:184)                                                    |                                                                                                                                                  |
| unlock()                                                                                                                                         |                                                                                                                                                  |
|   unlock$default(MutexImpl@1,null,1,null) at MutexLincheckTest.unlock(MutexTest.kt:17)                                                           |                                                                                                                                                  |
|     unlock(null) at Mutex$DefaultImpls.unlock$default(Mutex.kt:87)                                                                               |                                                                                                                                                  |
|       _state.READ: LockedQueue@1 at MutexImpl.unlock(Mutex.kt:433)                                                                               |                                                                                                                                                  |
|       removeFirstOrNull() at MutexImpl.unlock(Mutex.kt:317)                                                                                      |                                                                                                                                                  |
|         getNext(): LockCont@2 at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:282)                                             |                                                                                                                                                  |
|         remove(): false at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:284)                                                   |                                                                                                                                                  |
|         helpRemove() at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:285)                                                      |                                                                                                                                                  |
|         getNext(): LockCont@2 at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:282)                                             |                                                                                                                                                  |
|         remove(): false at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:284)                                                   |                                                                                                                                                  |
|         helpRemove() at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:285)                                                      |                                                                                                                                                  |
|         getNext(): LockCont@2 at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:282)                                             |                                                                                                                                                  |
|         remove(): false at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:284)                                                   |                                                                                                                                                  |
|         helpRemove() at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:285)                                                      |                                                                                                                                                  |
|         getNext(): LockCont@2 at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:282)                                             |                                                                                                                                                  |
|         remove(): false at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:284)                                                   |                                                                                                                                                  |
|         helpRemove() at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:285)                                                      |                                                                                                                                                  |
|         getNext(): LockCont@2 at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:282)                                             |                                                                                                                                                  |
|         remove(): false at LockFreeLinkedListNode.removeFirstOrNull(LockFreeLinkedList.kt:284)                                                   |                                                                                                                                                  |
|         (Obstruction freedom violated due to live lock)                                                                                          |                                                                                                                                                  |     
```