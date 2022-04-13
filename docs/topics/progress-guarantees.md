[//]: # (title: Progress guarantees)
Many concurrent algorithm provide non-blocking progress guarantees, such as lock- and wait-freedom.
As they are usually non-trivial, it is easy to add a bug that makes the algorithm blocking. 
Fortunately, Lincheck is able to find liveness bugs in the model checking mode.

Consider the `DataHolder` structure below that maintains a data of two `Long` variables (`first` and `second`),
updating it by a single thread via the `update(..)` function and allowing concurrent reads via the `read()` function.
The algorithm maintains a version of the data to synchronize concurrent readers and the writer,
incrementing it at the beginning and end of `update(..)`. Thus, the data version is even when
the data is safe to read and odd when there is a concurrent update. To make sure that the data is safe
to read during the whole `read()` operation, it reads the version at the beginning, checking that it is even,
reads the data without explicit synchronization, and checks that the version has not changed at the end;
restarting the operation on failure.

```kotlin
class DataHolder {
    private var first: Long = 42L
    private var second: Long = 7L
    @Volatile 
    private var version = 0L // we use it for synchronization
   
    // Invoked by a single thread 
    fun update(newFirst: Long, newSecond: Long) {
        version++ // lock the holder for reads
        first = newFirst
        second = newSecond
        version++ // release the holder for reads
    }

    fun read(): Pair<Long, Long> {
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

Let's check whether the algorithm is non-blocking:

1. To check the progress guarantee of the algorithm, set the `checkObstructionFreedom`
   option in `ModelCheckingOptions()`:

   ```kotlin
      ModelCheckingOptions().checkObstructionFreedom(true)
   ```
   
2. Also note that according to the contract, there is only one writer, so add `write(first, second)` operation 
   to a non-parallel operations group.

See the resulting test below:

```kotlin
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

class DataHolder {
   private var first: Int = 42
   private var second: Int = 7
   @Volatile
   private var version = 0 // we use it for synchronization

   // Invoked by a single thread
   fun update(newFirst: Int, newSecond: Int) {
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

@OpGroupConfig(name = "updater", nonParallel = true)
class DataHolderTest {
   private val dataHolder = DataHolder()

   @Operation(group = "updater")
   fun update(first: Int, second: Int) = dataHolder.update(first, second)

   @Operation
   fun read() = dataHolder.read()

   @Test
   fun runModelCheckingTest() = ModelCheckingOptions()
      .checkObstructionFreedom(true)
      .check(this::class)
}
```

Let's run the test — it fails with the following output:

```text
= Obstruction-freedom is required but an active lock has been found =
Execution scenario (parallel part):
| update(-4, -8) | read() |

= The following interleaving leads to the error =
Parallel part trace:
| update(-4, -8)                                                   |                                                              |
|   update(-4,-8) at DataHolderTest.update(DataHolderTest.kt:72)   |                                                              |
|     version.READ: 0 at DataHolder.update(DataHolderTest.kt:36)   |                                                              |
|     version.WRITE(1) at DataHolder.update(DataHolderTest.kt:36)  |                                                              |
|     switch                                                       |                                                              |
|                                                                  | read()                                                       |
|                                                                  |   read() at DataHolderTest.read(DataHolderTest.kt:75)        |
|                                                                  |     version.READ: 1 at DataHolder.read(DataHolderTest.kt:44) |
|                                                                  |     version.READ: 1 at DataHolder.read(DataHolderTest.kt:44) |
```

Lincheck has found the following blocking interleaving:

1. **T1:** The first thread (the writer) increments the version and switches its execution 
   to the second thread before updating the data.
2. **T2:** The second thread (the reader) reads the `version == 1` and 
   starts to spin in a loop, waiting for the concurrent `update(..)` to finish.

If the first thread hangs, the second thread stays in an active loop with no progress. 

The common non-blocking progress guarantees are (in order to weaken):

* **wait-freedom:** each operation completes in a bounded number of steps no matter what other threads do;
* **lock-freedom:** guarantees system-wide progress, so that at least one operation completes in a bounded number of steps, 
                    while a particular operation may be stuck;
* **obstruction-freedom:** – any operation can complete in a bounded number of steps if all the other threads pause.

For now, Lincheck supports only the _obstruction-freedom_ progress guarantee.
As most the real-world liveness bugs introduce unexpected blocking code,
obstruction-freedom check is beneficial for lock-free and wait-free algorithms.

> Get the full code of the example [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/DataHolderTest.kt).
>
{type="note"}

## What's next

Learn how Lincheck [verifies execution results](verification.md) for correctness and 
how to specify the sequential specification of the algorithm explicitly, making the testing more robust.