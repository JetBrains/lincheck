[//]: # (title: Configuring a testing strategy)
[//]: # (description: Learn about different options provided by Lincheck for model checking and stress testing.)

Lincheck supports various configuration options for testing strategies, including scenario generation,
detection of stalled execution, verification, and others.

## How to enable options

To enable an option for a testing strategy, set it in the strategy class:

```kotlin
@Test
fun modelCheckingTest() = ModelCheckingOptions()
    .iterations(100) // Specify the number of generated scenarios
    .check(this::class)
```

## Scenario minimization

By default, Lincheck tries to minimize the failed scenario by removing the operations that 
do not change the behavior of the test.

Set the `minimizeFailedScenario` option to `false` to see the full failed scenario.

<compare first-title="Full" second-title="Minimized">
<code-block lang="text">
| ------------------------- |
|    Thread 1    | Thread 2 |
| --------------------------|
| inc(): 1       |          |
| get(): 1       |          |
| get(): 1       |          |
| --------------------------|
| inc(): 4 [0,1] | inc(): 2 |
| get(): 4 [1,1] | inc(): 4 |
| get(): 4 [2,1] | get(): 4 |
| --------------------------|
| get(): 4       |          |
| get(): 4       |          |
| get(): 4       |          |
| --------------------------|
</code-block>
<code-block lang="text">
| ------------------- |
| Thread 1 | Thread 2 |
| ------------------- |
| inc(): 1 | inc(): 1 |
| ------------------- |
</code-block>
</compare>

## Scenario generation

| Option                    | Default value | Description                                                                                                                                 |
|---------------------------|---------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| `iterations`              | `100`         | The number of generated concurrent scenarios.                                                                                               |
| `invocationsPerIteration` | `10_000`      | The number of invocations for each concurrent scenario.                                                                                     |
| `threads`                 | `2`           | The number of threads in each scenario.                                                                                                     |
| `actorsBefore`            | `5`           | The number of operations called before the parallel section of a scenario.                                                                  |
| `actorsPerThread`         | `5`           | The number of operations in each thread in the parallel section of a scenario.                                                              |
| `actorsAfter`             | `5`           | The number of operations called after the parallel section of a scenario.                                                                   |
| `customScenarios`         | –             | A list of [custom concurrent scenarios](#defining-a-custom-scenario). The custom scenarios are executed before the randomly generated ones. |

### Defining a custom scenario

Lincheck uses a [domain-specific language](https://kotlinlang.org/docs/type-safe-builders.html) for defining custom scenarios:

```kotlin
@Test
fun test() = StressOptions()
    .customScenarios {
        initial {
            actor(SomeClass1::foo)
        }
        parallel {
            thread {
                actor(SomeClass1::buzz, 1)
                actor(SomeClass1::buzz, 2)
            }
            thread {
                actor(SomeClass1::buzz, 3)
            }
        }
        post {
            actor(SomeClass1::foo)
        }
    }
    .check(this::class)
```

Each scenario consists of three optional sections:

* `initial` – operations executed before the parallel part.
* `parallel` – thread definitions. Threads are defined using the `thread` block.
  The parallel section might contain multiple `thread` blocks.
* `post` – operations executed after the parallel part.

Operations are defined using the `actor(function, arg1, arg2, ...)` function. Operations within a single block are 
executed sequentially.

## Stalled execution detection

<table>
<tr><td>Option</td><td>Default value</td><td>Description</td></tr>
<tr>
    <td><code>timeoutMs</code></td>
    <td><code>3000</code></td>
    <td>Invocation timeout in milliseconds after which Lincheck reports a stalled execution.</td></tr>
<tr>
    <td><code>loopBound</code></td>
    <td><code>50</code></td>
    <td>The number of loop iterations after which Lincheck reports a stalled execution. 
        This option can only be applied to <a href="lincheck-testing-strategies.md#model-checking">model checking</a>.<br/><br/>
        Increase the value of <code>loopBound</code> if Lincheck falsely reports stalled execution for long loops.</td></tr>
<tr>
    <td><code>recursionBound</code></td>
    <td><code>20</code></td>
    <td>The number of recursive calls after which Lincheck reports a stalled execution. 
        This option can only be applied to <a href="lincheck-testing-strategies.md#model-checking">model checking</a>.<br/><br/>
        The value of <code>loopIterationsBeforeThreadSwitch</code> should be less than <code>loopBound</code>.</td></tr>
</table>

## Thread switching in loops
<primary-label ref="model-checking"/>

Use `loopIterationsBeforeThreadSwitch` to set the number of loop iterations a thread can perform before 
trying to switch to another thread. 

The value of `loopIterationsBeforeThreadSwitch` should be less than [`loopBound`](#stalled-execution-detection). 
Default: `10`

## Verification

<table>
<tr><td>Option</td><td>Default value</td><td>Description</td></tr>
<tr>
    <td><code>verifierClass</code></td>
    <td><code>LinearizabilityVerifier</code></td>
    <td>The verifier class used during the verification process:
        <!-- TODO: uncomment after the article is published
        <a href="lincheck-results-validation.md#verification-properties">verification process</a>:-->
        <list>
            <li><code>LinearizabilityVerifier</code></li>
            <li><code>SerializabilityVerifier</code></li>
            <li><code>QuiescentConsistencyVerifier</code></li>
        </list></td></tr>
<tr>
    <td><code>sequentialSpecification</code></td>
    <td>Same as the tested data structure.</td>
    <td>The sequential version of the tested data structure. This structure is used during the
        <a href="sequential-specification.md">verification process</a>.</td>
</tr>
</table>

## Progress guarantees
<primary-label ref="model-checking"/>

Set the `checkObstructionFreedom` option to `true` to verify the 
[obstruction-freedom guarantee](progress-guarantees.md) of the data structure operations.

## Library analysis
<primary-label ref="model-checking"/>

<table>
<tr><td>Option</td><td>Default value</td><td>Description</td></tr>
<tr>
    <td><code>stdLibAnalysisEnabled</code></td>
    <td><code>false</code></td>
    <td>By default, Lincheck does not verify the behavior of the operations from the standard library, 
        treating them as thread-safe. Set this option to <code>true</code> to enable analysis of standard
        library functions/classes.</td>
</tr>
<tr>
    <td><code>addGuarantee</code></td>
    <td>–</td>
    <td><a href="#defining-a-guarantee">Define guarantees</a> for thread-safe or irrelevant to analysis 
        methods using the <code>addGuarantee</code> option to exclude them from model checking.</td>
</tr>
</table>

### Defining a guarantee

To define a guarantee, use a builder chain: select classes, then methods, then the guarantee type.

```kotlin
@Test
fun modelCheckingTest() = ModelCheckingOptions()
        .addGuarantee(
            forClasses("java.util.concurrent.ConcurrentHashMap")
                .allMethods()
                .treatAsAtomic()
        )
        .check(this::class)
```

1. Select classes using one of the `forClasses` overloads:

   * `forClasses(vararg fullClassNames: String)` — matches classes if their full name is present in 
   the `fullClassNames` string.
   * `forClasses(vararg classes: KClass<*>)` — matches classes by reference.
   * `forClasses(classPredicate: (fullClassName: String) -> Boolean)` — match classes using a predicate 
   on full class names.

2. Select methods to apply the guarantee to:

   * `methods(methodNames: String)` – matches methods if their name is present in the `methodNames` string.
   * `methods(methodPredicate: (methodName: String) -> Boolean)` – matches methods using a predicate.
   * `allMethods()` – match all methods of the selected classes.

3. Choose the guarantee type:

   * `treatAsAtomic()` — treat each method as an atomic operation. Lincheck will not insert
   switch points inside the method call, but might add them before or after the call.

     Use `treatAsAtomic()` for methods that are known to be thread-safe.
   * `ignore()` — exclude the methods from analysis. Lincheck will not insert switch
   points inside, before, or after the method calls. 

     > If a method uses synchronization primitives internally (for instance, `synchronized` blocks), 
     > ignoring the method may cause Lincheck to deadlock.
     >
     {style="warning"}

     Use `ignore()` for methods that are irrelevant to analysis, such as logging or debugging utilities.


## What’s next

Learn how to [configure argument generation](operation-arguments.md) for operations used in 
Lincheck's execution scenarios.

## See also

* [Configuring algorithm constraints](constraints.md)
* [Checking for non-blocking progress guarantees](progress-guarantees.md)
* [Defining a sequential specification of an algorithm](sequential-specification.md)