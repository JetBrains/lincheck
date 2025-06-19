[//]: # (title: Operation arguments)

In this tutorial, you'll learn how to configure operation arguments.

Consider this straightforward `MultiMap` implementation below. It's based on the `ConcurrentHashMap`, internally storing
a list of values:

```kotlin
import java.util.concurrent.*

class MultiMap<K, V> {
    private val map = ConcurrentHashMap<K, List<V>>()
   
    // Maintains a list of values 
    // associated with the specified key.
    fun add(key: K, value: V) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }

    fun get(key: K): List<V> = map[key] ?: emptyList()
}
```

Is this `MultiMap` implementation linearizable? If not, an incorrect interleaving is more likely to be detected
when accessing a small range of keys, thus, increasing the possibility of processing the same key concurrently.

For this, configure the generator for a `key: Int` parameter:

1. Declare the `@Param` annotation.
2. Specify the integer generator class: `@Param(gen = IntGen::class)`.
   Lincheck supports random parameter generators for almost all primitives and strings out of the box.
3. Define the range of values generated with the string configuration `@Param(conf = "1:2")`.
4. Specify the parameter configuration name (`@Param(name = "key")`) to share it for several operations.

   Below is the stress test for `MultiMap` that generates keys for `add(key, value)` and `get(key)` operations in the
   range of `[1..2]`: 
   
   ```kotlin
   import java.util.concurrent.*
   import org.jetbrains.lincheck.check
   import org.jetbrains.lincheck.datastructures.*
   import org.jetbrains.kotlinx.lincheck.paramgen.*
   import org.junit.*
   
   class MultiMap<K, V> {
       private val map = ConcurrentHashMap<K, List<V>>()
   
       // Maintains a list of values 
       // associated with the specified key.
       fun add(key: K, value: V) {
           val list = map[key]
           if (list == null) {
               map[key] = listOf(value)
           } else {
               map[key] = list + value
           }
       }

       fun get(key: K): List<V> = map[key] ?: emptyList()
   }
   
   @Param(name = "key", gen = IntGen::class, conf = "1:2")
   class MultiMapTest {
       private val map = MultiMap<Int, Int>()
   
       @Operation
       fun add(@Param(name = "key") key: Int, value: Int) = map.add(key, value)
   
       @Operation
       fun get(@Param(name = "key") key: Int) = map.get(key)
   
       @Test
       fun stressTest() = StressOptions().check(this::class)
   
       @Test
       fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
   }
   ```

5. Run the `stressTest()` and see the following output:

   ```text
   = Invalid execution results =
   | ---------------------------------- |
   |    Thread 1     |     Thread 2     |
   | ---------------------------------- |
   | add(2, 0): void | add(2, -1): void |
   | ---------------------------------- |
   | get(2): [0]     |                  |
   | ---------------------------------- |
   ```
   
6. Finally, run `modelCheckingTest()`. It fails with the following output:

   ```text
   = Invalid execution results =
   | ---------------------------------- |
   |    Thread 1     |     Thread 2     |
   | ---------------------------------- |
   | add(2, 0): void | add(2, -1): void |
   | ---------------------------------- |
   | get(2): [-1]    |                  |
   | ---------------------------------- |
   
   ---
   All operations above the horizontal line | ----- | happen before those below the line
   ---

   The following interleaving leads to the error:
   | ---------------------------------------------------------------------- |
   |    Thread 1     |                       Thread 2                       |
   | ---------------------------------------------------------------------- |
   |                 | add(2, -1)                                           |
   |                 |   add(2,-1) at MultiMapTest.add(MultiMap.kt:31)      |
   |                 |     get(2): null at MultiMap.add(MultiMap.kt:15)     |
   |                 |     switch                                           |
   | add(2, 0): void |                                                      |
   |                 |     put(2,[-1]): [0] at MultiMap.add(MultiMap.kt:17) |
   |                 |   result: void                                       |
   | ---------------------------------------------------------------------- |
   ```

Due to the small range of keys, Lincheck quickly reveals the race: when two values are being added concurrently by the same key, 
one of the values may be overwritten and lost.

## Next step

Learn how to test data structures that set [access constraints on the execution](constraints.md),
such as single-producer single-consumer queues.