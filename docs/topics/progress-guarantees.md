[//]: # (title: Progress guarantees)

An algorithm without explicit synchronization may still be blocking, and checking for liveness bugs is a non-trivial task.
Lincheck can help you check your algorithm for liveness bugs using model checking.

Consider an example when you have some data with non-atomic reads and writes. One thread is updating the current data
calling `data.write(newData)` and several threads are calling `data.read()`.

To make reads and writes atomic without using locks, you can use versions of the data and get the following algorithm:

```kotlin
class DataHolder {
    var first: Int = 42
    var second: Int = 7
    @Volatile var version = 0

    fun write(newFirst: Int, newSecond: Int) { // single thread updater
        version++ // lock the holder for reads
        first = newFirst
        second = newSecond
        version++ // release the holder for reads
    }

    fun read(): Pair<Int, Int> {
        while(true) {
            val curVersion = version
            // Is there a concurrent update?
            if (curVersion % 2 == 1) continue
            // Read the data
            val first = this.first
            val second = this.second
            // Return if version is the same
            if (curVersion == version) return first to second
        }
    }
}
```

Check if this algorithm is non-blocking:

1. To check progress guarantees of the algorithm, set the `checkObstructionFreedom` option in `ModelCheckingOptions()`:

   ```kotlin
      ModelCheckingOptions().checkObstructionFreedom(true)
   ```
   
2. Also note that according to the contract, there is only one writer, so add `write(first, second)` operation to the non-parallel 
   operations group.

Here is the resulting test:

```kotlin
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

class DataHolder {
   var first: Int = 42
   var second: Int = 7
   @Volatile var version = 0

   fun write(newFirst: Int, newSecond: Int) { // single thread updater
      version++ // lock the holder for reads
      first = newFirst
      second = newSecond
      version++ // release the holder for reads
   }

   fun read(): Pair<Int, Int> {
      while(true) {
         val curVersion = version
         // Is there a concurrent update?
         if (curVersion % 2 == 1) continue
         // Read the data
         val first = this.first
         val second = this.second
         // Return if version is the same
         if (curVersion == version) return first to second
      }
   }
}

@OpGroupConfig(name = "writer", nonParallel = true)
class DataHolderTest {
   private val dataHolder = DataHolder()

   @Operation(group = "writer") // single thread writer
   fun write(first: Int, second: Int) = dataHolder.write(first, second)

   @Operation
   fun read() = dataHolder.read()

   @Test
   fun runModelCheckingTest() = ModelCheckingOptions()
      .checkObstructionFreedom(true)
      .check(this::class)
}
```

The test is failing with the following output:

```text
= Obstruction-freedom is required but an active lock has been found =
Execution scenario (parallel part):
| write(-4, -8) | read() |

= The following interleaving leads to the error =
Parallel part trace:
| write(-4, -8)                                                  |                                                                 |
|   write(-4,-8) at DataHolderTest.write(DataHolderTest.kt:72)   |                                                                 |
|     version.READ: 0 at DataHolder.write(DataHolderTest.kt:36)  |                                                                 |
|     version.WRITE(1) at DataHolder.write(DataHolderTest.kt:36) |                                                                 |
|     switch                                                     |                                                                 |
|                                                                | read()                                                          |
|                                                                |   read() at DataHolderTest.read(DataHolderTest.kt:75)           |
|                                                                |     version.READ: 1 at DataHolder.read(DataHolderTest.kt:44)    |
|                                                                |     version.READ: 1 at DataHolder.read(DataHolderTest.kt:44)    |
```

Model checking found the following blocking interleaving:

1. **T1:** The first thread (the writer) increments the version and switches its execution to the second thread before writing new values.    
2. **T2:** The second thread (the reader) reads the `version == 1` and starts to spin in a loop, waiting for the concurrently running update of the values to complete.

If the first thread halts, the second thread will stay in an active loop with no progress. Thus, the blocking state was detected.

The common progress guarantees of non-blocking algorithms are (in the order of weakening):

* _wait-freedom_ – operation may be completed in a bounded number of steps no matter what other processes do
* _lock-freedom_ – some process always makes progress, while a concrete operation may be stuck
* _obstruction-freedom_ – any operation may be completed in a bounded number of steps if all the other processes stop

For now, Lincheck may only test the algorithm for obstruction-freedom violation. However, as most of the practical liveness bugs
add an unexpected blocking code, obstruction-freedom check is very useful.

> Get the full code of the example [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/DataHolderTest.kt).
>
{type="note"}

## What's next

Learn how Lincheck [verifies execution results](verification.md) for correctness, how to check the sequential
behavior of the algorithm and make verification faster.