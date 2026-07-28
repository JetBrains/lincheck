[//]: # (title: Argument generation constraints)
[//]: # (description: Learn how to configure the generation of the operation arguments in Lincheck.)

To test a concurrent data structure, Lincheck generates a set of concurrent scenarios by placing the operations 
randomly in different threads and invoking them with random arguments.

You can constrain the range of operation arguments to increase the chance of finding a concurrency bug. 
For example, concurrent operations in a hash map are more likely to access the same key if the range of possible 
key values is limited. This enables Lincheck to expose race conditions and other concurrency bugs more efficiently.

To limit the range of the generated argument values in Lincheck:

1. Use the `@Param` annotation to declare an argument generator:

   ```kotlin
   @Param(name = "key", gen = IntGen::class, conf = "1:2")
   class MultiMapTest {
       // Tests
   }
   ```
   <!-- TODO: figure something out to not use line numbers -->

   * `name` – the name of the argument generator.
   * `gen` – the [type](#generator-types) of the generator.
   * `conf` – the configuration string for the generator. Here, Lincheck generates integer values from 1 to 2.

   > Lincheck provides generators for multiple value types. Each type uses a different configuration string template.
   >
   > Read more in the [Generator types](#generator-types) section.
   { style = "tip" }

2. Annotate the operations parameters with `@Param` to apply the constraints:

   ```kotlin
   ```
   {src="examples/ArgumentGenerationTest.kt" include-symbol="MultiMapTest.add,MultiMapTest.get"}

With the constraints in place, Lincheck generates scenarios using only values within the specified range:

```text
| ---------------------------------- |
|    Thread 1     |     Thread 2     |
| ---------------------------------- |
| add(2, 0): void | add(2, -1): void |
| ---------------------------------- |
| get(2): [-1]    |                  |
| ---------------------------------- |
```

## Generator types

Lincheck provides the following argument generator types:

<table>
    <tr>
      <th>Generator</th>
      <th>Configuration template</th>
      <th>Description</th>
    </tr>
    <tr>
      <td><code>IntGen</code></td>
      <td><code>"min:max"</code></td>
      <td>Generates <code>Int</code> values between <code>min</code> and <code>max</code>, inclusive. <br/><br/>
          If the configuration string is empty, uses the full integer range from <code>Int.MIN_VALUE</code> to 
          <code>Int.MAX_VALUE</code>. <br/><br/>
          Example:
          <code>"1:3" -> [1, 2, 3]</code></td>
    </tr>
    <tr>
      <td><code>StringGen</code></td>
      <td><code>"maxWordLength:alphabet"</code><br/><code>"maxWordLength"</code><br/><code>""</code></td>
      <td>Generates random string values up to <code>maxWordLength</code> from the provided <code>alphabet</code>.
          The default <code>alphabet</code> is <code>[a-zA-Z\d _]</code>. <br/>
          The default <code>maxWordLength</code> is <code>15</code>. <br/><br/>
          Example:
          <code-block lang="text">"2:abc" -> [
    "a", "b", "c",
    "aa", "bb", "cc",
    "ab", "bc", "ac",
    "ba", "cb", "ca"
]</code-block></td>
    </tr>
    <tr>
      <td><code>EnumGen</code></td>
      <td><code>"Enum.Const1,Enum.Const2,..."</code></td>
      <td>Generates a random value from the specified list of enum values. <br/><br/>
          Example:
          <code-block lang="text">"Enum.Const1,Enum.Const2" -> [
    Enum.Const1, 
    Enum.Const2
]</code-block></td>
    </tr>
    <tr>
      <td><code>BooleanGen</code></td>
      <td><code>""</code></td>
      <td>Generates <code>true</code> and <code>false</code> values. Does not require a specific configuration 
          string. <br/><br/>
          Example:
          <code>"" -> [true, false]</code></td>
    </tr>
    <tr>
      <td><code>DoubleGen</code></td>
      <td><code>"start:step:end"</code><br/><code>"start:end"</code><br/><code>""</code></td>
      <td>Generates <code>Double</code> values from <code>start</code> to <code>end</code>, incrementing by
          <code>step</code>. <br/><br/> 
          Default <code>step</code> value is <code>(end - start)/100</code>. <br/><br/> 
          If the configuration string is empty, generates values from <code>Int.MIN_VALUE</code> to 
          <code>Int.MAX_VALUE</code> with <code>step = 0.1</code>. <br/><br/>
          Example:
          <code>"0.0:0.1:1.0" -> [0.0, 0.1, 0.2, ..., 0.9, 1.0]</code></td>
    </tr>
    <tr>
      <td><code>FloatGen</code></td>
      <td><code>"start:step:end"</code><br/><code>"start:end"</code><br/><code>""</code></td>
      <td>Same as <code>DoubleGen</code> with values converted to <code>Float</code>. <br/><br/>
          Example:
          <code>"0.0:0.1:1.0" -> [0.0, 0.1, 0.2, ..., 0.9, 1.0]</code></td>
    </tr>
    <tr>
      <td><code>LongGen</code></td>
      <td><code>"min:max"</code></td>
      <td>Same as <code>IntGen</code> with values converted to <code>Long</code>. <br/><br/>
          Example:
          <code>"1:3" -> [1, 2, 3]</code></td>
    </tr>
    <tr>
      <td><code>ShortGen</code></td>
      <td><code>"min:max"</code></td>
      <td>Generates <code>Short</code> values between <code>min</code> and <code>max</code>, inclusive. <br/><br/>
          If the configuration string is empty, uses the full short integer range from <code>-32768</code> 
          to <code>32767</code>. <br/><br/>
          Example:
          <code>"1:3" -> [1, 2, 3]</code></td>
    </tr>
    <tr>
      <td><code>ByteGen</code></td>
      <td><code>"min:max"</code></td>
      <td>Generates <code>Byte</code> values between <code>min</code> and <code>max</code>, inclusive. <br/><br/>
          If the configuration string is empty, uses the full byte range from <code>-128</code> to 
          <code>127</code>. <br/><br/>
          Example:
          <code>"1:3" -> [1, 2, 3]</code></td>
    </tr>
    <tr>
      <td><code>ThreadIdGen</code></td>
      <td><code>""</code></td>
      <td>Returns the ID number of the current thread. Does not require a specific configuration string. <br/><br/>
          Example:
          <code>"" -> [1, 2]</code></td>
    </tr>
</table>

## What's next

Learn how to [restrict certain operations to a single thread](lincheck-operation-execution-options.md) in Lincheck.

## See also

* [Checking for non-blocking progress guarantees](lincheck-progress-guarantees.md)
* [Defining sequential specification of the algorithm](lincheck-results-validation.md)