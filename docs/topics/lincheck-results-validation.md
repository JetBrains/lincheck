[//]: # (title: Results verification and validation)
[//]: # (description: Learn about different verification properties provided by Lincheck, how to declare a)
[//]: # (sequential version of a concurrent data structure, and how to validate the results of a concurrent algorithm.)

After executing the scenarios generated for a concurrent data structure, Lincheck verifies the results against the 
specified verification property (for example, linearizability) and optionally checks the final 
data structure state against the user-provided validation function.

## Verification

During the verification process, Lincheck tries to find a sequential execution of the operations in the concurrent 
scenario that achieves the same results as the concurrent execution:

![A diagram of the steps performed by Lincheck during tests. At the verification step, Lincheck compares a 
concurrent execution to different sequential executions.](verification-process.svg){width=700}

Depending on the [verification property](#verification-properties), there might be additional restrictions placed 
on the sequential execution. If no sequential execution matching the verification property can produce the observed 
results, Lincheck reports an error.

By default, Lincheck verifies the results of the concurrent execution against linearizability. To specify a different
verification property, use the `verifierClass` option:

```kotlin
@Test
fun modelCheckingTest() = ModelCheckingOptions()
    .verifierClass(SerializabilityVerifier::class)
    .check(this::class)
```

### Verification properties

Lincheck can verify the results of the concurrent execution against one of the following properties:

* `LinearizabilityVerifier` – the default option. The concurrent execution is valid if there is a sequential execution
  that preserves the ["happens-before"](https://en.wikipedia.org/wiki/Happened-before) relations between the operations
  in the concurrent execution.
* `QuiescentConsistencyVerifier` – same as linearizability, but the "happens-before" constraints are not applied to the
  operations annotated with `@QuiescentConsistent`:

    ```kotlin
    @Operation
    @QuiescentConsistent
    fun foo() = { ... }
    ```
  
  > `QuiescentConsistencyVerifier` does not track actual quiescent points. The verifier can potentially miss bugs that
  > occur across the boundaries of quiescent points.
  >
  {style="note"}

* `SerializabilityVerifier` – the concurrent execution is valid if there exists some sequential execution (in any order) 
  that leads to the same results as the concurrent execution regardless of the "happens-before" constraints. Can be 
  used for structures where the relative order of the concurrent operations does not matter.

### Sequential specification

During the verification process, Lincheck constructs a sequential execution using the operations of the
_concurrent_ data structure. 

You can specify a sequential data structure with matching operations to:
* Make sure that the concurrent data structure provides the same results as the sequential one. 
  
  Typically, single-threaded implementations are simpler than thread-safe implementations, and therefore can be much 
  more easily verified for correctness (for example, `HashMap` and `ConcurrentHashMap`,
  `LinkedList` and `ConcurrentLinkedQueue`).

  By comparing the execution results of two versions of a structure, you can make sure that the more complex concurrent 
  structure behaves similarly to a simpler structure in a single-threaded environment.
* Verify both sequential correctness and concurrency safety in a single test.

![A diagram of the verification process in Lincheck. Lincheck compares a concurrent execution to different 
sequential executions. Sequential executions use the operations of the specified sequential 
version of the structure.](verification-process-seq.svg){width=700}

To specify a sequential version of a data structure:

1. Implement a data structure with sequential versions of all concurrent methods tested by Lincheck.
2. Specify the data structure using the `sequentialSpecification()` option:

    ```kotlin
    @Test
    fun stressTest() = StressOptions()
        .sequentialSpecification(SequentialStructure::class)
        .check(this::class)
    ```

An example of a Lincheck test that uses a single-threaded `LinkedList` as the sequential specification of
`ConcurrentLinkedQueue`:

```kotlin
class ConcurrentLinkedQueueTest {
    private val s = ConcurrentLinkedQueue<Int>()

    @Operation
    fun add(value: Int) = s.add(value)

    @Operation
    fun poll(): Int? = s.poll()

    @Test
    fun stressTest() = StressOptions()
        .sequentialSpecification(SequentialQueue::class.java)
        .check(this::class)
}

class SequentialQueue {
    private val s = LinkedList<Int>()

    fun add(x: Int) = s.add(x)
    fun poll(): Int? = s.poll()
}
```

### Example: understand the difference between linearizability and serializability

Consider the following data structure:

```kotlin
class ConcurrentQueue {
    private val elements: MutableList<Int> = ArrayList()

    fun put(x: Int) = synchronized(this) {
        elements += x
    }

    fun poll(): Int? = synchronized(this) {
        if (elements.isEmpty()) return null
        elements.shuffle()
        elements.removeAt(0)
    }
}
```

We have intentionally introduced incorrect behavior to this concurrent structure: it stores the elements like 
a typical queue but returns them randomly.

Using Lincheck, we can show that this concurrent structure is serializable, but not linearizable:

1. Implement a sequential version of the queue that correctly stores and returns the elements:

    ```kotlin
    class SequentialQueue {
        private val elements: MutableList<Int> = ArrayList()
    
        fun put(x: Int) {
            elements += x
        }
    
        fun poll(): Int? = if (elements.isEmpty()) null else elements.removeAt(0)
    }
    ```

2. Create a test class and declare the `put()` and `poll()` operations:

    ```kotlin
    @Param(name = "value", gen = IntGen::class, conf = "1:2")
    class ConcurrentQueueTest {
        private val q = ConcurrentQueue()
    
        @Operation
        fun put(@Param(name = "value") x: Int) = q.put(x)
    
        @Operation
        fun poll(): Int? = q.poll()
    }
    ```

3. Declare and run a serializability test:

    ```kotlin
    @Test
    fun serializabilityTest() = ModelCheckingOptions()
        .actorsBefore(0)
        .actorsAfter(0)
        .actorsPerThread(2)
        .threads(2)
        // Use the `SerializabilityVerifier`
        .verifier(SerializabilityVerifier::class.java)
        // Specify the sequential version of the structure
        .sequentialSpecification(SequentialQueue::class.java)
        .check(this::class.java)
    ```

   It should pass successfully.
4. Declare and run a linearizability test:

    ```kotlin
    @Test
    fun linearizabilityTest() = ModelCheckingOptions()
        .actorsBefore(0)
        .actorsAfter(0)
        .actorsPerThread(2)
        .threads(2)
        // Show the full failed scenario
        .minimizeFailedScenario(false)
        // Specify the sequential version of the structure
        .sequentialSpecification(SequentialQueue::class.java)
        .check(this::class.java)
    ```
   
    The test should fail with the following report:

    ```text
    | -------------------- |
    | Thread 1  | Thread 2 |
    | -------------------- |
    |           | put(2)   |
    |           | put(1)   |
    | put(3)    |          |
    | poll(): 1 |          |
    | -------------------- |
    ```

5. Analyze the report. 

    Because the serializability test passes, there exists _some_ sequential order of `SequentialQueue` 
    operations that produces `poll(): 1`, for example:

   ![An animation of the operations in a two-threaded scenario reordered into a single-threaded scenario. 
     Thread 2 is executed first and has two operations: `put(2)` then `put(1)`. 
     Thread 1 is executed second and has two operations: `put(3)` then `poll(): 1`. 
     The single-threaded scenario has the operations in the following order: `put(1)`, `put(2)`, `put(3)`, `poll(): 1`. 
     Because `put(1)` is now the first operation, `poll()` returns the value `1` correctly.](reorder.gif){width=500}

    However, linearizability places an additional restriction on the order of operations: if operation `A` has 
    finished before operation `B` has begun in the concurrent execution, in the sequential execution, `A` must be 
    executed before `B`. An example of a linearizable execution would look like this:

   ![An animation of the operations in a two-threaded scenario reordered into a single-threaded scenario. 
     Thread 1 is executed first and has two operations: `put(1)` then `put(2)`. 
     Thread 2 is executed second and has two operations: `poll(): 1` then `poll(): 2`. 
     The single-threaded scenario has the operations in the following order: `put(1)`, `put(2)`, `poll(): 1`, `poll(): 2`. 
     All `poll()` operations return the correct values.](reorderLin.gif){width=500}

    Because Lincheck cannot reorder the `put()` operations during verification, it cannot find
    a sequential execution that complies with linearizability restrictions. This results in a failed test.

## Validation

By default, Lincheck does not validate the state of the concurrent data structure after executing a generated scenario.
To specify a validation function, use the `@Validate` annotation on a method of the test class:

```kotlin
@Validate
fun validate() {
    // Check some property of the data structure.
    // Throw an exception if the invariant is violated.
    check(size >= 0) { "Size must be non-negative, but was $size" }
}
```

The validation function should:
* Accept no arguments.
* Throw an exception if the data structure is in an invalid state.

## See also

* [Configuring argument generation constraints](lincheck-argument-generation-constraints.md)
* [Configuring operation execution](lincheck-operation-execution-options.md)
* [Checking for non-blocking progress guarantees](lincheck-progress-guarantees.md)