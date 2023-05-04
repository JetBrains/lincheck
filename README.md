# kotlinx-lincheck

[![Kotlin Beta](https://kotl.in/badges/beta.svg)](https://kotlinlang.org/docs/components-stability.html)
[![JetBrains official project](https://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](https://opensource.org/licenses/MPL-2.0)

Lincheck is a practical and user-friendly framework for testing concurrent algorithms on the JVM. It provides a simple
and declarative way to write concurrent tests.

With the Lincheck framework, instead of describing how to perform tests, you can specify _what to test_
by just declaring all the data structure operations to examine. After that, Lincheck automatically 
generates a set of random concurrent scenarios,
examines them using either stress-testing or bounded model checking, and
verifies that the results of each invocation satisfy the required correctness property (linearizability by default).

## Documentation and Presentations

Please see the [official tutorial](/docs/topics/lincheck-guide.md) that showcases Lincheck features through examples.

You may also be interested in the following public talks:

* "Lincheck: Testing concurrency on the JVM" workshop by Maria Sokolova: [Part 1](https://www.youtube.com/watch?v=YNtUK9GK4pA), [Part 2](https://www.youtube.com/watch?v=EW7mkAOErWw). Hydra 2021

## Using in Your Project

To use Lincheck in your project, you need to add it as a dependency. If you use Gradle, add the following lines to `build.gradle.kts`:

```kotlin
repositories {
   mavenCentral()
}

dependencies {
   // Lincheck dependency
   testImplementation("org.jetbrains.kotlinx:lincheck:2.17")
}
```

### Java 9+ 
To use model checking strategy for Java 9 and later, add the following JVM properties:

```text
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports java.base/jdk.internal.util=ALL-UNNAMED
```

They are required if the testing code uses classes from the `java.util` package since
some of them use `jdk.internal.misc.Unsafe` or similar internal classes under the hood.

If you use Gradle, add the following lines to `build.gradle.kts`:

```
tasks.withType<Test> {
    jvmArgs("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.util=ALL-UNNAMED")
}
```


## Example 

The following Lincheck test easily finds a bug in the standard Java's `ConcurrentLinkedDeque`:

```kotlin
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.*
import java.util.concurrent.*

class ConcurrentDequeTest {
    private val deque = ConcurrentLinkedDeque<Int>()

    @Operation
    fun addFirst(e: Int) = deque.addFirst(e)

    @Operation
    fun addLast(e: Int) = deque.addLast(e)

    @Operation
    fun pollFirst() = deque.pollFirst()

    @Operation
    fun pollLast() = deque.pollLast()

    @Operation
    fun peekFirst() = deque.peekFirst()

    @Operation
    fun peekLast() = deque.peekLast()

    // Run Lincheck in the stress testing mode
    @Test
    fun stressTest() = StressOptions().check(this::class)

    // Run Lincheck in the model checking testing mode
    @Test
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
}
```

When running `modelCheckingTest(),` Lincheck not only detects a bug but also provides a comprehensive interleaving trace that explains it:

```text
= Invalid execution results =
Init part:
[addLast(4): void]
Parallel part:
| pollFirst(): 4 | addFirst(-4): void       |
|                | peekLast():   4    [-,1] |
---
values in "[..]" brackets indicate the number of completed operations 
in each of the parallel threads seen at the beginning of the current operation
---

= The following interleaving leads to the error =
Parallel part trace:
| pollFirst()                                                                                               |                      |
|   pollFirst(): 4 at ConcurrentDequeTest.pollFirst(ConcurrentDequeTest.kt:39)                              |                      |
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

## Contributing 

See [Contributing Guidelines](CONTRIBUTING.md).

## Acknowledgements

This is a fork of the [Lin-Check framework](https://github.com/Devexperts/lin-check) by Devexperts.
