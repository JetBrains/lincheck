# Developing concurrent data structures is H.A.R.D.
(todo: replace the title)

## Bugs Found

### Bug 1: `java.util.concurrent.ConcurrentLinkedDeque` 
Source: Java Standard Library (still present in the latest JDK version)


### Bug 2: jctools `NonBlockingHashMapLong`
Source: jctools 3.1.0 or older.  
This bug [was found](https://github.com/JCTools/JCTools/issues/319) and later fixed using Lincheck.

### Bug 3: jctools `NonBlockingIdentityHashMap`
Source: jctools 3.1.0 or older.  
This bug [was found](https://github.com/JCTools/JCTools/pull/328) and later fixed using Lincheck.

### Bug 4: Bronson et al. `SnapTree`
Source: [authors' implementation](https://github.com/nbronson/snaptree).  
N. G. Bronson, J. Casper, H. Chafi and K. Olukotun. A practical concurrent binary search tree. In PPoPP, 2010.

### Bug 5: Drachsler et al. `LogicalOrderingAVL`
Source: [authors' implementation](https://github.com/gramoli/synchrobench/blob/master/java/src/trees/lockbased/LogicalOrderingAVL.java).  
D. Drachsler, M. Vechev and E. Yahav. Practical concurrent binary search trees via logical ordering. In PPoPP, pages 343–356, 2014.

### Bug 6: Sagonas and Winblad `CATree`
Source: [authors' implementation](https://github.com/gramoli/synchrobench/blob/master/java/src/trees/lockbased/CATreeMapAVL.java).  
K. Sagonas and K. Winblad. Contention Adapting Search Trees. In ISPDC 2015.

### Bug 7: Aksenov et al. `ConcurrencyOptimalTreeMap`
Source: [authors' implementation](https://github.com/gramoli/synchrobench/blob/master/java/src/trees/lockbased/ConcurrencyOptimalTreeMap.java).  
V. Aksenov, V. Gramoli, P. Kuznetsov, A. Malova, S. Ravi. A Concurrency-Optimal Binary Search Tree. In Euro-Par, 2017. arXiv:1705.02851

### Bug 8: Gallagher et al. Concurrent Suffix Tree
Source: [implementation](https://github.com/npgall/concurrent-trees), [artifact](https://mvnrepository.com/artifact/com.googlecode.concurrent-trees/concurrent-trees).


## Running Tests
All bugs can be reproduced using
```
./gradlew test
```

The tests are located in `/test/kotlin/`.  

## Acknowledgements
We thank Anton Udovichenko for [finding](https://github.com/AnthonyUdovichenko/concurrent-algorithms-testing) many of these bugs.