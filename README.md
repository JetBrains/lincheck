# Bugs discovered with Lincheck

This repository consists a set of bugs originally discovered with Lincheck.

## `ConcurrentLinkedDeque` in Java
Source: Java Standard Library (still present in the latest JDK version)
The bug [was originally detected](https://bugs.openjdk.org/browse/JDK-8256833) by Lincheck.

## `NonBlockingHashMapLong` in JCTools
Source: [JCTools](https://github.com/JCTools/JCTools) 3.1.0 or older.  
This bug [was found](https://github.com/JCTools/JCTools/issues/319) and later fixed using Lincheck.

## `SnapTree` by Bronson et al. 
Source: [authors' implementation](https://github.com/nbronson/snaptree).  
N. G. Bronson, J. Casper, H. Chafi and K. Olukotun. A practical concurrent binary search tree. In PPoPP, 2010.

## `LogicalOrderingAVL` by Drachsler et al. 
Source: [authors' implementation](https://github.com/gramoli/synchrobench/blob/master/java/src/trees/lockbased/LogicalOrderingAVL.java).  
D. Drachsler, M. Vechev and E. Yahav. Practical concurrent binary search trees via logical ordering. In PPoPP, 2014.

## `CATree` by Sagonas and Winblad 
Source: [authors' implementation](https://github.com/gramoli/synchrobench/blob/master/java/src/trees/lockbased/CATreeMapAVL.java).  
K. Sagonas and K. Winblad. Contention Adapting Search Trees. In ISPDC, 2015.

## `ConcurrencyOptimalTree` by Aksenov et al. 
Source: [authors' implementation](https://github.com/gramoli/synchrobench/blob/master/java/src/trees/lockbased/ConcurrencyOptimalTreeMap.java).  
V. Aksenov, V. Gramoli, P. Kuznetsov, A. Malova, S. Ravi. A Concurrency-Optimal Binary Search Tree. In Euro-Par, 2017. arXiv:1705.02851

## `ConcurrentSuffixTree` by Gallagher et al. 
Source: [implementation](https://github.com/npgall/concurrent-trees), [artifact](https://mvnrepository.com/artifact/com.googlecode.concurrent-trees/concurrent-trees).


# Running Tests
All these bugs can be reproduced by running the following command:
```
./gradlew test
```

The report will be located in `/test/kotlin/`.  

# Acknowledgements
We thank Anton Udovichenko for [finding](https://github.com/AnthonyUdovichenko/concurrent-algorithms-testing) many of these bugs.
