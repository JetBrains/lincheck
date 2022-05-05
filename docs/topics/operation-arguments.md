[//]: # (title: How to generate operation arguments)

In this guide section, we will learn how to configure the operation arguments.
Consider the straightforward `MultiMap` implementation below. 
It bases on the `ConcurrentHashMap`, storing a list of values internally.

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

Consider testing concurrent execution of `add(key, value)` and `get(key)` operations. The incorrect interleaving is more 
likely to be detected if you increase the contention by accessing a small range of keys.

For this, configure the generator for a `key: Int` parameter:

1. Declare the `@Param` annotation.
2. Specify the integer generator class: `@Param(gen = IntGen::class)`.
   Lincheck supports random parameter generators for almost all primitives and strings out of the box.

3. Define the range of values generated with the string configuration: `@Param(conf = "1:2")`.
4. Specify the parameter configuration name (`@Param(name = "key")`) to share it for several operations.

   Below is the stress test for `MultiMap` that generates keys for `add(key, value)` and `get(key)` operations in the
   range of `[1..2]`: 
   
   ```kotlin
   import java.util.concurrent.*
   import org.jetbrains.kotlinx.lincheck.annotations.*
   import org.jetbrains.kotlinx.lincheck.check
   import org.jetbrains.kotlinx.lincheck.paramgen.*
   import org.jetbrains.kotlinx.lincheck.strategy.stress.*
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
   }
   ```

5. Run the `stressTest()` and see the following output:

   ```text
   = Invalid execution results =
   Parallel part:
   | add(1, 1): void | add(1, 4): void |
   Post part:
   [get(1): [4]]
   ```

Due to the small range of keys, Lincheck quickly revealed the race bug: when two values are being added concurrently by the same key, 
one of the values may be overwritten and lost.

> Get the full code [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MultiMapTest.kt).
>
{type="note"}

## What's next

`MultiMap` implementation uses a complex `j.u.c.ConcurrentHashMap` data structure as a building block, 
the internal synchronization of which significantly increases the number of possible interleavings, 
so it may take a while to find a bug.

Considering the `j.u.c.ConcurrentHashMap` implementation correct, you can speed up the testing 
and increase the coverage with the [modular testing](modular-testing.md) feature for the model checking mode.