[//]: # (title: Lincheck guide)

With the Lincheck framework, instead of describing how to perform tests, you can specify what to test by declaring all the
operations to examine along with the correctness property (linearizability is the default one).

This guide will help you get in touch with the framework and try the most useful features with examples. Explore all the
Lincheck features step-by-step: 

* [Write your first test](introduction.md)
* [Choose the testing strategy](testing-strategies.md)
* [Configure arguments passed to test operations](parameter-generation.md)
* [Use modular testing to optimize and increase testing coverage](modular-testing.md) 
* [Consider data structure constraints in tests](constraints.md)
* [Check the algorithm for progress guarantees](progress-guarantees.md)
* [Verify execution results](verification.md)
* [Test blocking data structures](blocking-data-structures.md)

## Additional references

* [Workshop. Lincheck: Testing concurrency on the JVM (Part 1)](https://www.youtube.com/watch?v=YNtUK9GK4pA) (Hydra
  2021, EN)
* [Workshop. Lincheck: Testing concurrency on the JVM (Part 2)](https://www.youtube.com/watch?v=EW7mkAOErWw) (Hydra
  2021, EN)
* [Lincheck. Testing concurrent data structures in Java](https://www.youtube.com/watch?v=YAb7YoEd6mM) (Heisenbug 2019,
  RU)
* [Testing concurrent algorithms with Lincheck](https://nkoval.com/talks/#lincheck-joker-2019) (Joker 2019, RU)
* [Lincheck: testing concurrent data structures on Java](https://nkoval.com/talks/#lincheck-hydra-2019) (Hydra 2019, RU)
* [Lock-free algorithms testing](https://nkoval.com/talks/#lock_free_algorithms_testing) (Joker 2017, RU)