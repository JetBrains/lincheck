[//]: # (title: Lincheck guide)

Lincheck is a practical and user-friendly framework for testing concurrent algorithms on the JVM. It provides a simple
and declarative way to write concurrent tests.

With the Lincheck framework, instead of describing how to perform tests, you can specify _what to test_ 
by declaring all the operations to examine and the required correctness property. As a result, a typical
concurrent Lincheck test contains only about 15 lines.

When given a list of operations, Lincheck automatically:

* Generates a set of random concurrent scenarios.
* Examines them using either stress-testing or bounded model checking.
* Verifies that the results of each invocation satisfy the required correctness property (linearizability is the default
one).

## Add Lincheck to your project

To enable the Lincheck support, include the corresponding repository and dependency to the Gradle configuration. In your
`build.gradle(.kts)` file, add the following:

<tabs group="build-script">
<tab title="Kotlin" group-key="kotlin">

```kotlin
repositories {
    mavenCentral()
}
 
dependencies {
    testImplementation("org.jetbrains.kotlinx:lincheck:%lincheckVersion%")
}
```

</tab>
<tab title="Groovy" group-key="groovy">

```groovy
repositories {
    mavenCentral()
}

dependencies {
    testImplementation "org.jetbrains.kotlinx:lincheck:%lincheckVersion%"
}
```

</tab>
</tabs>

## Explore Lincheck

This guide will help you get in touch with the framework and try the most useful features with examples. Learn the
Lincheck features step-by-step:

1. [Write your first test with Lincheck](introduction.md)
2. [Choose your testing strategy](testing-strategies.md)
3. [Configure operation arguments](operation-arguments.md)
4. [Use modular testing in model checking](modular-testing.md) 
5. [Consider popular algorithm constraints](constraints.md)
6. [Check the algorithm for non-blocking progress guarantees](progress-guarantees.md)
7. [Define sequential specification of the algorithm](sequential-specification.md)

## Additional references

* Workshop. Lincheck: Testing concurrency on the JVM: [Part 1](https://www.youtube.com/watch?v=YNtUK9GK4pA),
  Hydra 2021, EN
* Workshop. Lincheck: Testing concurrency on the JVM: [Part 2](https://www.youtube.com/watch?v=EW7mkAOErWw),
  Hydra 2021, EN
* [Lincheck. Testing concurrent data structures in Java](https://www.youtube.com/watch?v=YAb7YoEd6mM), Heisenbug 2019,
  RU
* [Testing concurrent algorithms with Lincheck](https://www.youtube.com/watch?v=9cB36asOjPo), Joker 2019, RU
* [Lincheck: testing concurrent data structures on Java](https://www.youtube.com/watch?v=hwbpUEGHvvY), Hydra 2019, RU
* [Lock-free algorithms testing](https://www.youtube.com/watch?v=_0_HOnTSS0E), Joker 2017, RU
