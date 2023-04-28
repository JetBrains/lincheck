# Artifact for the Lincheck paper @ CAV '23 

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)

Lincheck is a new practical and user-friendly framework for testing concurrent data structures on the Java Virtual Machine (JVM). It provides a simple and declarative way to write concurrent tests. Instead of describing how to perform the test, users specify what to test by declaring all the operations to examine; the framework automatically handles the rest. As a result, tests written with Lincheck are concise and easy to understand.

The artifact presents a collection of Lincheck tests that discover new bugs in popular libraries and implementations from the concurrency literature -- they are listed in Table 1, Section 3. To evaluate the performance of Lincheck analysis, the collection of tests also includes those which check correct data structures and, thus, always succeed. Similarly to Table 2, Section 3, the experiments demonstrate the reasonable time to perform a test. Finally, Lincheck provides user-friendly output with an easy-to-follow trace to reproduce a detected error, significantly simplifying further investigation.

## Lincheck 

In addition to the paper, you may find interested the following resources:

* Official [GitHub repository](https://github.com/Kotlin/kotlinx-lincheck/) of Lincheck
* [User guide](https://kotlinlang.org/docs/lincheck-guide.html) that showcases Lincheck features through examples
* "Lincheck: Testing concurrency on the JVM" workshop by Maria Sokolova ([part 1](https://www.youtube.com/watch?v=YNtUK9GK4pA), [part 2](https://www.youtube.com/watch?v=EW7mkAOErWw)), Hydra '21

## Artifact structure

TODO

## How to run the tests

To run the tests, please use

* `./gradlew build` on Linux or macOS
* `gradlew build` on Windows

Some tests detect bugs and fail, so it is expected for the build to fail as well.
After executing the command, please find the report in `./build/reports/tests/test/index.html`. 

> This is a standard test execution report by Gradle.
> Press "Classes" to get a list of all (not only failed) tests.
> After that, by pressing on the test you want to check, you will obtain the execution times of 
> both _fast_ and _long_ configurations. However, if the test has failed, you will see the error information, 
> which includes the Lincheck output. In this case, to see the execution time, you need to press "Tests". 

The following tests check correct data structures for evaluating Lincheck's performance and, therefore, should succeed:

* `ConcurrentHashMapTest` (hashtable implementation in the standard Java library)
* `ConcurrentLinkedQueueTest` (Michael-Scott queue implementation in the standard Java library)
* `LockFreeTaskQueueTest` (quiescent consistent queue in Kotlin Coroutines internals)
* `SemaphoreTest` (semaphore implementation for Kotlin Coroutines)

Other tests should fail; check the build report in `./build/reports/tests/test/index.html`.
For your convenience, you can also access these reports without running the tests in [Reports](REPORTS.md) page.
Below, we discuss these bugs in more detail.

## List of previously unknown bugs detected with Lincheck

### 1. `ConcurrentLinkedDeque` in Java Standard Library
**Source:** Java Standard Library (still present in the latest JDK version).    
**Test:** [ConcurrentLinkedDequeTest](test/ConcurrentLinkedDequeTest.kt)  

This bug has long-standing history. At least twice `ConcurrentLinkedDeque` was reported to be not linearizable before. At least twice this issue was "fixed" ([one](https://bugs.openjdk.org/browse/JDK-8188900), [two](https://bugs.openjdk.org/browse/JDK-8189387)).

Even after all these fixes, we still are able to detect non-linearizability with a simple Lincheck test.  We reported the error [here](https://bugs.openjdk.org/browse/JDK-8256833) and the issue is currently unresolved.
The whole situation shows how hard it is to test linearizability and how easy errors persist if there are no good linearizability tests.

An example of non-linearizable behaviour found by Lincheck is when `ConcurrentLinkedDeque.pollFirst()` removes an element, which is known to be not first, due to concurrent modifications.

### 2. `AbstractQueueSynchronizer` in Java Standard Library
**Source:** Java Standard Library (still present in the latest JDK version).  
**Test report:** Please see [Reports](REPORTS.md) page  

Notably, this bug was introduced in the latest JDK versions (17+).

We show this `AbstractQueueSynchronizer` bug on `Semaphore` primitive, but many other Java concurrency primitives (such as `CyclicBarrier` and `CountDownLatch`) also employ this data structure, and thus, have the same bug.
In the case of `Semaphore`, one interrupted `Semaphore.acquire` can lead to a state when all threads are waiting (parked), while there is still a semaphore available.

> We do not provide a test to reproduce this bug, as it would require a Lincheck feature that is currently under development and has not been released yet.

### 3. `Mutex` in Kotlin Coroutines
**Source:** Kotlin Coroutines 1.5.0 or older.  
**Tests:** [MutexTest](test/MutexTest.kt)

Lincheck [helped to find](https://github.com/Kotlin/kotlinx.coroutines/issues/2590) this bug in the Kotlin Coroutines library and fix it.
The issue is caused not by non-linearizability but by violation of progress guarantees. The code was expected to be non-blocking, however, it was possible to reach a state where a thread is trapped in a live lock loop.

### 4. `NonBlockingHashMapLong` in JCTools
**Source:** [JCTools](https://github.com/JCTools/JCTools) 3.1.0 or older.  
**Test:** [NonBlockingHashMapLongTest](test/NonBlockingHashMapLongTest.kt)

This bug [was found](https://github.com/JCTools/JCTools/issues/319) and later fixed using Lincheck.

The issue was that, for high performance, failed modifications sometimes pretended that they were linearized before the operation that caused the failure. This optimization leads to non-linearizability when operations should return the old value. 

### 5. `ConcurrentSuffixTree` & `ConcurrentRadixTree` by Gallagher et al.
**Source:** [implementation](https://github.com/npgall/concurrent-trees), [artifact](https://mvnrepository.com/artifact/com.googlecode.concurrent-trees/concurrent-trees).  
**Tests:** [ConcurrentSuffixTreeTest](test/ConcurrentSuffixTreeTest.kt), [ConcurrentRadixTreeTest](src/test/kotlin/ConcurrentRadixTreeTest.kt)

In this popular concurrency library, the authors claim that their trees offer a consistent view for readers. However, as can be seen in the Lincheck test reports, when a modification is in progress, a reader thread can see keys for some string `s` in `getKeysContaining(s)`, but not see these keys for a substring of `s`, which is clearly not a consistent view, even in terms of sequential properties of the data structure.

### 6. `SnapTree` by Bronson et al. 
**Source:** [authors' implementation](https://github.com/nbronson/snaptree).  
N. G. Bronson, J. Casper, H. Chafi and K. Olukotun. A practical concurrent binary search tree. In PPoPP, 2010.    
**Test:** [SnapTreeTest](test/SnapTreeTest.kt)

We also discover an error in well-known Bronson et al. AVL tree, which was one of the first scalable concurrent search trees, and whose concurrency protocols were adopted by many other concurrent trees. 

The error lies in an incorrect assumption during `firstKey`/`lastKey` searches. Since the rightmost and leftmost nodes of the tree are not internal (i.e. do not have both children), the author assumed that they will not be marked as removed, because only internal nodes can be marked as removed. However, due to concurrency the found extreme nodes can become internal and later be marked, so the implementation throws an expection in this case.

### 7. `LogicalOrderingAVL` by Drachsler et al. 
**Source:** [authors' implementation](https://github.com/gramoli/synchrobench/blob/master/java/src/trees/lockbased/LogicalOrderingAVL.java).    
D. Drachsler, M. Vechev and E. Yahav. Practical concurrent binary search trees via logical ordering. In PPoPP, 2014.
**Test:** [LogicalOrderingAVLTest](test/LogicalOrderingAVLTest.kt)

We believe that the flaw in Drachsler et al. deadlock-freedom argument was originally found by Trevor Brown. The argument was based on the invariant that parents are locked before children. However, during rebalancing, due to rotations this order can change. 

The deadlock is easily found by Lincheck when two concurrent threads simultaneously modify the tree. Basically, due to concurrent tree rotation, operations try to take two locks in different order, and thus, come to a deadlock.

### 8. `CATree` by Sagonas and Winblad 
**Source:** [authors' implementation](https://github.com/gramoli/synchrobench/blob/master/java/src/trees/lockbased/CATreeMapAVL.java).    
K. Sagonas and K. Winblad. Contention Adapting Search Trees. In ISPDC, 2015.  
**Test:** [CATreeTest](test/CATreeTest.kt)

Another deadlock is present in Contention Adapting Search Tree. When a thread tries to lock the whole tree (e.g., for `size` operation) and another thread clears the data structure changing the root, the
first thread can accidentally try to unlock the new root, which it did not
lock, making the root locked forever. 

### 9. `ConcurrencyOptimalTree` by Aksenov et al. 
**Source:** [authors' implementation](https://github.com/gramoli/synchrobench/blob/master/java/src/trees/lockbased/ConcurrencyOptimalTreeMap.java).    
V. Aksenov, V. Gramoli, P. Kuznetsov, A. Malova, S. Ravi. A Concurrency-Optimal Binary Search Tree. In Euro-Par, 2017. arXiv:1705.02851  
**Test:** [ConcurrencyOptimalMapTest](test/ConcurrencyOptimalMapTest.kt)

In Concurrency Optimal Search Tree, two concurrent `putIfAbsent` operations can throw `NullPointerException`. Basically, if an operation re-tries, while the tree is
still empty, this operation does an illegal memory access, since the authors
simply forgot to handle this case.
