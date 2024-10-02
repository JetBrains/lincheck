# Lincheck Benchmarks

This document describes various benchmarks used within the Lincheck project
and contains instructions on how to run them to produce reports and plots.

### Benchmark categories

Benchmarks are split into several categories with 
each category aiming to evaluate particular aspects of the framework.
All benchmarks within each category have similar structure
and collect the same set of metrics.

#### Category `running`

Benchmarks in this category aim to evaluate the overall performance of 
running Lincheck tests using different strategies.
In each benchmark, specific correctly implemented 
data structure or synchronization primitive is tested by Lincheck
(since the implementations are assumed to be correct, the tool should not report any bugs).
For each benchmark, the Lincheck is requested to generate fixed number of scenarios, 
and run each scenario fixed number of times with fixed strategy. 
The overall running time of the benchmark is measured.

**Parameters:**
- tested data-structure or synchronization primitive
- number of iterations
- number of invocations per iteration
- Lincheck strategy to run the scenarios 

**Metrics:**
- overall running time

#### Collected statistics

Although the main metric for benchmarks from this category is overall running time,
the benchmarking tool is actually capable of collecting more statistical information,
which might be useful when debugging the performance issues of the tool.

For each benchmark, the following statistics are collected:
- overall running time
- number of iterations
- total number of invocations (across all iterations)
- per-scenario statistics (see below)

For each scenario within each benchmark, the following statistics are collected:
- overall running time
- number of invocations
- running time of each invocation (optional)

### Running benchmarks

Individual benchmarks can be run via the `jvmBenchmark` gradle task
using the standard test task arguments:

```
./gradlew :jvmBenchmark --tests ConcurrentHashMapBenchmark
```

To run all benchmarks and produce the reports, use the `jvmBenchmarkSuite` gradle task:

```
./gradlew :jvmBenchmarkSuite
```

By default, this task collects only per-iteration statistics, 
without the information about the running time of each invocation.
It is because this information significantly increases the size of the produced reports.
If you need per-invocation statistics, run the `jvmBenchmarkSuite` task 
with the `statisticsGranularity` option set to `perInvocation`:

```
./gradlew :jvmBenchmarkSuite -DstatisticsGranularity=perInvocation
```

The `jvmBenchmarkSuite` task produces the following reports:
- `benchmarks-results.json` - report in JSON format containing all collected statistics
- `benchmarks-results.txt` - report in simple text format containing only the overall running time of each benchmark

These reports can then be uploaded to an external storage, 
or analyzed locally, using the shipped plotting facilities (see below).

### Drawing plots

The Lincheck benchmarking project includes a script 
to draw various plots based on collected benchmarks statistics.
The script utilizes the [Lets-Plot Kotlin](https://github.com/JetBrains/lets-plot-kotlin) library.

The following types of plots are currently supported.
- Benchmarks' total running time bar plot.
- Invocation average running time per scenario size bar plot (for each benchmarked data structure).
- Invocation time scatter plot (for each benchmarked data structure and strategy).

The gradle task `runBenchmarkPlots` can be invoked to produce the plots. 
It expects the path to the `.json` report file to be provided as the first argument:

```
./gradlew :runBenchmarkPlots --args="benchmarks-results.json" 
```

By default, this task produces all available plots in `.html` format and stores them into `lets-plot-images` directory.

To see a description of all available task options, run the following command:

```
./gradlew :runBenchmarkPlots --args="--help"
```





