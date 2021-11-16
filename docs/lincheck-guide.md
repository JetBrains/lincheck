[//]: # (title: Lincheck guide)

## Lincheck 
`Lincheck` is a practical tool for testing concurrent algorithms in a simple and declarative way. In short, instead of describing how to perform the test, with `Lincheck` you specify what to test by declaring all the operations to examine along with the correctness property (linearizability is the default one).

This hands-on guide will help you get in touch with the framework and try the most useful features by examples.

## Table of Contents

* [Create Your First Test](introduction.md)
* [Stress Testing and Model Checking Modes](testing-strategies.md)
* [Single Producer/Consumer Constraint](constraints.md)
* [Operation Parameter Generation](parameter-generation.md)
* [Modular Testing](modular-testing.md)
* [Progress Guarantees](progress-guarantees.md)
* [Verification and Correctness Properties](verification.md)
* [Testing Blocking Algorithms for Kotlin Coroutines](blocking-data-structures.md)

## Given Talks

* [Workshop. Lincheck: Testing concurrency on the JVM (Part 1)](https://www.youtube.com/watch?v=YNtUK9GK4pA), 
  [Workshop. Lincheck: Testing concurrency on the JVM (Part 2)](https://www.youtube.com/watch?v=EW7mkAOErWw) (Hydra 2021, EN)
* [Lincheck. Testing concurrent data structures in Java](https://www.youtube.com/watch?v=YAb7YoEd6mM) (Heisenbug 2019, RU)
* [Testing concurrent algorithms with Lincheck](https://nkoval.com/talks/#lincheck-joker-2019) (Joker 2019, RU)
* [Lincheck: testing concurrent data structures on Java](https://nkoval.com/talks/#lincheck-hydra-2019) (Hydra 2019, RU)
* [Lock-free algorithms testing](https://nkoval.com/talks/#lock_free_algorithms_testing) (Joker 2017, RU)