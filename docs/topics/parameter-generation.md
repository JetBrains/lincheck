[//]: # (title: Parameter generation)

Learn how to configure the generation of arguments for test operations using this implementation of the custom `MultiMap`
class backed with `ConcurrentHashMap` that contains a race bug:

```kotlin
import java.util.concurrent.ConcurrentHashMap

//sampleStart
class MultiMap {
    val map = ConcurrentHashMap<Int, List<Int>>()

    // adds the value to the list by the given key
    // contains the race :(
    fun add(key: Int, value: Int) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }

    fun get(key: Int) = map.get(key)
}
//sampleEnd
```

Consider testing concurrent execution of `add(key, value)` and `get(key)` operations. The incorrect interleaving is more 
likely to be detected if you increase the contention to access the small range of keys.

For this, configure the generator for a `key: Int` parameter:

1. Declare the `@Param` annotation.
2. Specify the integer generator class: `@Param(gen = IntGen::class)`.
   Lincheck supports random parameter generators for almost all primitives and strings out of the box.

3. Define the range of values generated with the string configuration: `@Param(conf = "1:2")`.
4. Specify the parameter configuration name (`@Param(name = "key")`) to share it for several operations.

   Below is the stress test for `MultiMap` that will generate keys for `add(key, value)` and `get(key)` operations in the
   range of `[1..2]`: 
   
```kotlin
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test

class MultiMap {
    val map = ConcurrentHashMap<Int, List<Int>>()

    // adds the value to the list by the given key
    // contains the race :(
    fun add(key: Int, value: Int) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }

    fun get(key: Int) = map.get(key)
}

//sampleStart
@Param(name = "key", gen = IntGen::class, conf = "1:2")
class MultiMapTest {
    private val map = MultiMap()

    @Operation
    fun add(@Param(name = "key") key: Int, value: Int) = map.add(key, value)

    @Operation
    fun get(@Param(name = "key") key: Int) = map.get(key)

    @Test
    fun stressTest() = StressOptions().check(this::class)
}
//sampleEnd
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

> Get the full code of the example [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MultiMapTest.kt).
>
{type="note"}

## What's next

`MultiMap` implementation uses `java.util.concurrent.ConcurrentHashMap` as a building block, and testing using the model
checking strategy may take a while due to the significant number of interleavings to check.

If you consider the implementation of the `ConcurrentHashMap` to be correct, you can optimize and increase coverage of model
checking using [modular testing](modular-testing.md).