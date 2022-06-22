[//]: # (title: Lincheck guide)

Lincheck is a practical and user-friendly framework for testing concurrent algorithms on the JVM.
It provides a simple and declarative way to write concurrent tests:
instead of describing how to perform the test, you specify _what to test_ 
by declaring all the operations to examine, along with the required correctness property. 
As a result, a typical concurrent test via Lincheck contains only about 15 lines.
Given the list of operations, Lincheck automatically 
(1) generates a set of random concurrent scenarios, 
(2) examines them using either stress-testing or bounded model checking, and 
(3) verifies that the results of each invocation satisfy the required correctness property
(linearizability is the default one).

This guide will help you get in touch with the framework and master the most useful features by examples.
We highly recommend exploring the Lincheck features step-by-step:

1. [Write your first test with Lincheck](introduction.md)
2. [Stress testing and model checking](testing-strategies.md)
3. [How to generate operation arguments](operation-arguments.md)
4. [Modular testing in the model checking mode](modular-testing.md) 
5. [Popular algorithm constraints](constraints.md)
6. [Checking for non-blocking progress guarantees](progress-guarantees.md)
7. [Define sequential specification of the algorithm](sequential_specification.md)

## Additional references
* [Workshop. Lincheck: Testing concurrency on the JVM (Part 1)](https://www.youtube.com/watch?v=YNtUK9GK4pA) (Hydra
  2021, EN)
* [Workshop. Lincheck: Testing concurrency on the JVM (Part 2)](https://www.youtube.com/watch?v=EW7mkAOErWw) (Hydra
  2021, EN)
* [Lincheck. Testing concurrent data structures in Java](https://www.youtube.com/watch?v=YAb7YoEd6mM) (Heisenbug 2019,
  RU)
* [Testing concurrent algorithms with Lincheck](https://nkoval.com/talks/#lincheck-joker-2019) (Joker 2019, RU)
* [Lincheck: testing concurrent data structures on Java](https://www.youtube.com/watch?v=hwbpUEGHvvY) (Hydra 2019, RU)
* [Lock-free algorithms testing](https://www.youtube.com/watch?v=_0_HOnTSS0E&t=1s) (Joker 2017, RU)
