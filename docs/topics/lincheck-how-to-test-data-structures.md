[//]: # (title: How to test data structures)
[//]: # (description: Learn how to test concurrent data structures with Lincheck:)
[//]: # (set up a test and understand the internals of the testing process.)

Lincheck provides a declarative interface for testing concurrent data structures.
Instead of describing how to perform the test, you declare all the operations that
need to be tested, and Lincheck generates concurrent execution scenarios, runs them,
and analyzes the results.

To test a data structure in Lincheck:

1. Create a test class:

   ```kotlin
   class NewStructureTest {
       // Tests
   }
   ```

2. Create a class property that holds an instance of your structure:

   ```kotlin
   private val structure = NewStructure<Int>()
   ```

3. Declare the operations you want to test as member functions and annotate them with
   `@Operation`. This annotation tells Lincheck which methods to include when generating
   execution scenarios:

   ```kotlin
   @Operation
   fun foo(value: Int) = structure.foo(value)

   @Operation
   fun buzz(): Int? = structure.buzz()
   ```

4. Declare a test function as a member function using `ModelCheckingOptions()` or
   `StressOptions()`. Annotate it with `@Test`:

   ```kotlin
   @Test
   fun modelCheckingTest() = ModelCheckingOptions()
       .check(this::class)
   ```

   > Learn about the differences between model checking and stress testing in the
   > [Testing Strategies](lincheck-testing-strategies.md) article.
   >
   {style=”tip”}

5. Run the test. If it fails, Lincheck generates an error report with the scenario
   and execution trace that led to incorrect behavior.

## The testing process

When testing a data structure, Lincheck generates a list of execution scenarios,
runs them, and analyzes the results.

Consider this `Counter` data structure:

![A diagram of a `Counter` data structure with two methods: `inc()` and `dec()`](counter_structure.svg){width=150}

To test it, Lincheck performs the following steps:

1. Generates a list of random execution scenarios by randomly placing declared
   operations across different threads:

   ![A diagram of four execution scenarios. In each scenario, operations are placed in different orders 
   in two threads.](execution_scenarios.svg){width=400}

   You can specify the number of threads and the number of operations per thread
   using the [configuration options](lincheck-testing-strategies-options.md#scenario-generation)
   provided by Lincheck.

2. Executes the generated scenarios using the specified testing strategy:
   [model checking or stress testing](lincheck-testing-strategies.md). Each of the
   generated scenarios is executed multiple times to examine different execution schedules:

   ![A diagram of four execution schedules. All schedules correspond to a single execution scenario.
   In each schedule, the operations interrupt each other at different times.](execution_schedules.svg){width=400}

3. Verifies the results of the execution against the correctness property. By default,
   it’s [linearizability](https://en.wikipedia.org/wiki/Linearizability).

   ![A diagram of the verification process. The results of one execution schedule are compared
   to the results of the same operations executed sequentially.](verification.svg){width=300}

   <!-- TODO: uncomment after the article is published
   At this step, Lincheck can also validate the structure if provided with a
   [validation function](lincheck-results-validation.md). -->

## Example: test an implementation of a Treiber stack structure

Consider this _incorrect_ implementation of a [Treiber Stack](https://en.wikipedia.org/wiki/Treiber_stack):

```kotlin
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.annotations.*
import org.jetbrains.lincheck.strategy.managed.modelchecking.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TreiberStack<E> {
    private val top = AtomicReference<Node<E>?>(null)

    fun push(item: E) {
        val newHead = Node(item)
        var oldHead: Node<E>?

        do {
            oldHead = top.get()
            newHead.next = oldHead
        } while (!top.compareAndSet(oldHead, newHead))
    }

    fun pop(): E? {
        val oldHead = top.get()

        if (oldHead == null) {
            return null
        }

        val newHead = oldHead.next
        top.compareAndSet(oldHead, newHead)

        // Bug: by the time `pop()` finishes execution,
        // another thread might have already popped this item.
        return oldHead.item
    }

    private class Node<E>(
        val item: E,
        var next: Node<E>? = null
    )
}
```

You can test this structure with Lincheck to examine how the injected bug affects
the behavior of the program:

1. Create a test structure:

    ```kotlin
   class TreiberStackTest {
       private val stack = TreiberStack<Int>()
  
       @Operation
       fun push(value: Int) = stack.push(value)
  
       @Operation
       fun pop(): Int? = stack.pop()
  
       @Test
       fun modelCheckingTest() = ModelCheckingOptions()
           .check(this::class)
   }
   ```

2. Run the test. Lincheck generates an error report and provides the execution
   scenario that causes incorrect behavior:

   ```text
   | ------------------------------ |
   |   Thread 1    |    Thread 2    |
   | ------------------------------ |
   | push(1): void |                |
   | ------------------------------ |
   | pop(): 1      | push(-1): void |
   | ------------------------------ |
   | pop(): -1     |                |
   | pop(): 1      |                |
   | ------------------------------ |
   ```

   This diagram shows the distribution of the operations across different threads and
   the return values of these operations. Lincheck also provides the specific thread
   interleaving that leads to incorrect results:

   ```text
   | ----------------------------------------------------- |
   |                  Thread 1                  | Thread 2 |
   | ----------------------------------------------------- |
   | push(1)                                    |          |
   | ----------------------------------------------------- |
   | pop(): 1                                   |          |
   |   stack.pop(): 1                           |          |
   |     top.get(): Node#1                      |          |
   |     switch                                 |          |
   |                                            | push(-1) |
   |     oldHead.getNext(): null                |          |
   |     top.compareAndSet(Node#1, null): false |          |
   |     oldHead.getItem(): 1                   |          |
   |   result: 1                                |          |
   | ----------------------------------------------------- |
   | pop(): -1                                  |          |
   | pop(): 1                                   |          |
   | ----------------------------------------------------- |
   ```

   Because the implementation does not account for another thread interrupting the
   `pop()` function, `pop()` returns `1` twice, which should not be possible.

3. Fix the data structure. A correct implementation updates the `oldHead` variable
   to the most recent value before returning the results:

   ```kotlin
   fun pop(): E? {
       var oldHead: Node<E>?
       var newHead: Node<E>?
 
       do {
           oldHead = top.get()
           if (oldHead == null) return null
           newHead = oldHead.next
       } while (!top.compareAndSet(oldHead, newHead))
 
       return oldHead.item
   }
   ```

## What’s next

Learn about [testing strategies](lincheck-testing-strategies.md) available in Lincheck.

## See also

* [Generating operation arguments](operation-arguments.md)
* [Configuring algorithm constraints](constraints.md)
* [Checking for non-blocking progress guarantees](progress-guarantees.md)
* [Defining sequential specification of the algorithm](sequential-specification.md)