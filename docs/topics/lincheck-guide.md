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
    testImplementation("org.jetbrains.lincheck:lincheck:%lincheckVersion%")
}
```

</tab>
<tab title="Groovy" group-key="groovy">

```groovy
repositories {
    mavenCentral()
}

dependencies {
    testImplementation "org.jetbrains.lincheck:lincheck:%lincheckVersion%"
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
4. [Consider popular algorithm constraints](constraints.md)
5. [Check the algorithm for non-blocking progress guarantees](progress-guarantees.md)
6. [Define sequential specification of the algorithm](sequential-specification.md)

## Additional references
* "How we test concurrent algorithms in Kotlin Coroutines" by Nikita Koval: [Video](https://youtu.be/jZqkWfa11Js). KotlinConf 2023
* "Lincheck: Testing concurrency on the JVM" workshop by Maria Sokolova: [Part 1](https://www.youtube.com/watch?v=YNtUK9GK4pA), [Part 2](https://www.youtube.com/watch?v=EW7mkAOErWw). Hydra 2021
