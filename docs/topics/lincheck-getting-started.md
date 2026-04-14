[//]: # (title: Getting started with Lincheck)
[//]: # (description: This quickstart will guide you through setting up Lincheck, writing your first Lincheck tests,)
[//]: # (and interpreting the test reports.)

This quickstart will guide you through setting up Lincheck, writing your first Lincheck tests, and interpreting the 
test reports.

You will:
* Create a new IntelliJ IDEA project and install Lincheck.
* Write your first concurrent test and run it with Lincheck.
* Create a concurrent data structure and test it with Lincheck using two testing strategies.

## Create a project

Open an existing Kotlin project in IntelliJ IDEA or
[create a new one](https://kotlinlang.org/docs/jvm-get-started.html).

## Add dependencies

To use Lincheck in the project, add the corresponding dependencies to your build configuration:

<tabs group="build-script">
<tab title="Kotlin" group-key="kotlin">

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.lincheck:lincheck:%lincheckVersion%")
    testImplementation(kotlin("test"))
}
```

</tab>
<tab title="Groovy" group-key="groovy">

```groovy
// build.gradle
repositories {
    mavenCentral()
}

dependencies {
    testImplementation "org.jetbrains.lincheck:lincheck:%lincheckVersion%"
    testImplementation "org.jetbrains.kotlin:kotlin-test"
}
```

</tab>
<tab title="Maven" group-key="maven">

```xml
<!-- pom.xml -->
<project>
    <dependencies>
         <dependency>
             <groupId>org.jetbrains.lincheck</groupId>
             <artifactId>lincheck</artifactId>
             <version>${lincheck.version}</version>
             <scope>test</scope>
         </dependency>
         <dependency>
             <groupId>org.jetbrains.kotlin</groupId>
             <artifactId>kotlin-test</artifactId>
             <scope>test</scope>
         </dependency>
    </dependencies>
    ...
</project>
```

</tab>
</tabs>

## Write your first test

For a basic concurrent test, create a test function that describes what operations should be executed in each 
thread and the expected assertions. Lincheck explores possible thread interleavings of the program using 
[model checking](testing-strategies.md#how-model-checking-works) and provides an error report in case of 
incorrect behavior.

1. In the `src/test` directory, create a `CounterTest.kt` file.
2. Import the `org.jetbrains.lincheck`, `kotlinx.concurrent`, and `kotlin.test` libraries: 
    ```kotlin
    import org.jetbrains.lincheck.*
    import kotlin.concurrent.*
    import kotlin.test.*
    ```

3. Write a test that creates a variable and two threads manipulating that variable:

    ```kotlin
    class CounterTest {
        @Test // Test function declaration
        fun test() = Lincheck.runConcurrentTest {
            var counter = 0


            // Increments the counter concurrently
            val t1 = thread { counter++ }
            val t2 = thread { counter++ }


            // Waits for the threads to finish
            t1.join()
            t2.join()


            // Checks that both increments have been applied
            assertEquals(2, counter)
        }
    }
    ```

4. Run the test. Lincheck generates a report with a thread interleaving that led to incorrect behavior:

    > Install the [Lincheck plugin](https://plugins.jetbrains.com/plugin/24171-lincheck) to
    > visualize the error trace.
    {style="note"}
   
    ```text
    | ------------------------------------------------------------------------------- |
    |                   Main Thread                   |   Thread 1    |   Thread 2    |
    | ------------------------------------------------------------------------------- |
    | thread(block = Lambda#2): Thread#1              |               |               |
    | thread(block = Lambda#3): Thread#2              |               |               |
    | switch (reason: waiting for Thread 1 to finish) |               |               |
    |                                                 |               | run()         |
    |                                                 |               |   counter ➜ 0 |
    |                                                 |               |   switch      |
    |                                                 | run()         |               |
    |                                                 |   counter ➜ 0 |               |
    |                                                 |   counter = 1 |               |
    |                                                 |               |   counter = 1 |
    | Thread#1.join()                                 |               |               |
    | Thread#2.join()                                 |               |               |
    | counter.element ➜ 1                             |               |               |
    | assertEquals(2, 1): threw AssertionFailedError  |               |               |
    | ------------------------------------------------------------------------------- |
    ```

    Lincheck found a thread interleaving where one of the `inc()` operations overwrites the `counter` value.
    <procedure title="Step-by-step report explanation" id="report_explanation" collapsible="true">
        <step>In Thread 2, the JVM reads the initial <code>counter</code> value.</step>
        <step>The execution switches from Thread 2 to Thread 1.</step>
        <step>In Thread 1, the JVM increments the counter. All steps of the <code>inc()</code> operation are performed 
              without interruptions: reading the value from the variable, incrementing the value, and writing the value 
              back to the variable.</step>
        <step>The execution switches back to Thread 2.</step>
        <step>In Thread 2, the JVM increments the value acquired at step 1 and writes the result to the 
              <code>counter</code> variable.</step>
    </procedure>

## Write a test for a data structure

In addition to basic concurrent tests, Lincheck supports a declarative approach to testing concurrent data structures.

To test a data structure in Lincheck, you only need to declare the concurrent methods of the structure and a test 
function. Lincheck generates random concurrent scenarios, executes them using the specified testing strategy, and 
provides error reports.

In this section, you will test a simple counter:

1. In the `src/test` directory, create a `CounterStructureTest.kt` file.
2. Import the `lincheck.datastructures` and `kotlin.test` libraries:

    ```kotlin
    import org.jetbrains.lincheck.datastructures.*
    import kotlin.test.*
    ```

3. Create a `Counter` structure:
    ```kotlin
    class Counter {
        @Volatile
        private var value = 0
    
        fun inc(): Int = ++value
        fun get() = value
    }
    ```
   
4. Create a `CounterStructureTest` class. Set the initial state of the structure and mark the concurrent operations 
   of the structure with the `@Operation` annotation:
    ```kotlin
    class CounterStructureTest {
        // Initial state
        private val c = Counter()
    
        // Concurrent operations
        @Operation
        fun inc() = c.inc()
    
        @Operation
        fun get() = c.get()
    }
    ```
   
5. In the `CounterTest` class, declare a test function using `ModelCheckingOptions()`:
    ```kotlin
    @Test
    fun stressTest() = ModelCheckingOptions().check(this::class)
    ```
   
    > Learn how model checking works in the [Testing Strategies](testing-strategies.md#how-model-checking-works) 
    > article.
    {style=”tip”}

6. Run the test. Lincheck generates an error report with the concurrent scenario and the specific thread interleaving 
   that led to incorrect behavior:
    ```text
    | ------------------- |
    | Thread 1 | Thread 2 |
    | ------------------- |
    | inc(): 1 | inc(): 1 |
    | ------------------- |
    ```

    ```text
    | ------------------------ |
    | Thread 1 |   Thread 2    |
    | ------------------------ |
    |          | inc(): 1      |
    |          |   c.inc(): 1  |
    |          |     value ➜ 0 |
    |          |     switch    |
    | inc(): 1 |               |
    |          |     value = 1 |
    |          |     value ➜ 1 |
    |          |   result: 1   |
    | ------------------------ |
    ```

## What’s next

Read more about the declarative approach to testing data structures and supported testing strategies in the
[Testing strategies](testing-strategies.md) article.