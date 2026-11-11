# Java memory model litmus tests

340 Java litmus tests for [JCStress](https://openjdk.org/projects/code-tools/jcstress/),
covering coherence, causality, publication, atomics and fences.
The repository includes the harness and builds a standalone jar.

## Build and run

Requirements: JDK 11 or newer, Maven, and Python 3.9 or newer for the recording scripts.
Run from the repository root:

```sh
mvn -pl litmus-tests -am clean package -Dmaven.javadoc.skip=true -DskipTests
python3 litmus-tests/verify.py
python3 litmus-tests/run.py --output litmus-tests/results/run-001 --budget 1h
```

This runs all 340 tests and saves the results in `litmus-tests/results/run-001/`.
Use a fresh output directory for each run.
The runner also records the machine it runs on in `system.txt`
(see [Experimental setup](#experimental-setup)).
Some tests use six actors, so provide at least six logical CPUs with working `LOCAL` affinity (the default).
The budget is a target for the stress phase; JVM startup and scheduling can extend the elapsed time.
`-DskipTests` skips harness unit tests during the build; the Python command runs the litmus tests.

To list tests or run one group:

```sh
java -jar litmus-tests/target/jcstress.jar -l
python3 litmus-tests/run.py --output litmus-tests/results/skiplist-001 \
  --tests 'org.openjdk.jcstress.tests.jmm.SkipListPublicationTests.*' --budget 10m
```

`run.py --help` lists CPU, affinity, fork and JVM options.
`--jvm-args` sets base JVM options; the harness can still vary VM flags and scheduling.
To run on another machine, copy `litmus-tests/target/jcstress.jar` and execute:

```sh
java -jar jcstress.jar -t 'org.openjdk.jcstress.tests.jmm.*' -tb 1h -r results
```

This produces an HTML report and a compressed binary result file in the working directory.
For JSON and machine metadata, copy `run.py` and `export_results.py` alongside the jar and run
`python3 run.py --jar jcstress.jar --output results --budget 1h`.

## Results

The Python runner saves:

| File | Contents |
| --- | --- |
| `html/index.html` | JCStress report with outcome counts |
| `results.json` | Counts aggregated by test and outcome |
| `raw.jsonl` | One JSON record per configuration, including JVM flags and status |
| `jcstress-results-*.bin.gz` | Native JCStress results for later processing |
| `system.txt` | Processor, presumed memory model, OS, memory, JDK and harness build, as `param: value` lines |
| `environment.json` | Command, timestamps, JDK, raw `lscpu` output, jar hash, missing tests and errors |
| `selected-tests.txt`, `manifest.json` | Test names and source metadata |
| `jcstress.jar`, `run.log` | Exact executable jar and harness output |

In `results.json`, each fully qualified test name maps to
`outcomes` (tuple strings and counts), `samples`, `configs`, and `statuses`.
For example, `"outcomes": {"1, 0": 123}` records 123 observations of that tuple.
The runner exits unsuccessfully if tests are missing, have no samples, or report harness errors.

Tests record all outcomes as `ACCEPTABLE`; selected targets are `ACCEPTABLE_INTERESTING`.
These labels collect observations rather than classify JMM legality.
A tuple absent from a finite run is not necessarily forbidden.

Tuple components follow the `r1`, `r2`, … assignments in each test's `@Arbiter` method.
The embedded [manifest](litmus-tests/src/main/resources/META-INF/jmm-litmus/manifest.json)
records test names, source hashes, actor counts and result components.
CoRR class suffixes identify the write mode and the two read modes.
The `TestCasAcross…` cases report `(first, second, success)` with target `(2, 3, true)`;
`weakCompareAndSetPlain` can fail spuriously.
Causality cases 14 and 15 use one iteration;
cases 19 and 20 start and join a child thread before actor 1's first memory access.

To re-export binary results, use the jar saved with the run:

```sh
java -cp litmus-tests/results/run-001/jcstress.jar \
  org.openjdk.jcstress.tests.jmm.tools.DumpResults \
  litmus-tests/results/run-001/jcstress-results-*.bin.gz > raw.jsonl
python3 litmus-tests/export_results.py raw.jsonl results.json
```

## Experimental setup

Each run is characterised by the processor, its memory model, the OS, the memory, the JDK, and the
harness parameters. Two scripts collect them as `param: value` lines:

```sh
python3 litmus-tests/system_info.py                    # the machine this command runs on
python3 litmus-tests/summarize_run.py litmus-tests/results/run-001   # a recorded run
```

`system_info.py` reports the CPU model, vendor, sockets, cores, threads, frequency and caches,
the memory model presumed from the instruction-set architecture (`x86-TSO`, `Armv8-A`, `IBM Power`, …),
virtualization and container hints, RAM, OS and kernel, the JDK version and its default JIT flags,
and the harness build (JCStress version, jar hash, git revision).
`run.py` saves the same report as `system.txt` next to the results,
so pass `--java` and `--jar` only when running it by hand for another JDK or jar.
On Linux it reads `lscpu`, `/proc` and `/sys`; on macOS it reads `sysctl`.
The memory model is inferred from the architecture, not measured, and the report says so.

`summarize_run.py` combines `system.txt`, `environment.json`, `run.log` and the exported results
into one list: machine, start time and elapsed wall-clock time, time budget, forks, CPUs and affinity mode,
the JVM configurations JCStress probed and ran (`-Xint`, C1 only, C2 only, tiered, C2 stress flags),
the scheduling classes per actor count, and the number of configurations, samples and tests with
more than one observed outcome.
Pass `--json` to either script for machine-readable output.

The raw sources behind the summary, all inside the results directory:

| Question | Where to look |
| --- | --- |
| Which machine, OS, JDK and harness build? | `system.txt`; `environment.json` keeps the full `lscpu` output and `java -version` |
| Exact command, start and end time, exit code? | `environment.json` (`command`, `started_utc`, `finished_utc`, `exit_code`) |
| JVM configurations, CPU topology, scheduling classes, strides, time budget? | `run.log` header, printed by JCStress before the first test |
| Per-configuration JVM flags, compile mode, fork and status? | `raw.jsonl`, one record per configuration |
| Which tests ran and from which sources? | `selected-tests.txt`, `manifest.json` |

Memory limits are set by JCStress itself: each forked test JVM gets a 256 MB heap
(option `-hs`; `run.log` reports *Trimming down the VM heap size*),
and the test state footprint is capped at a quarter of that
(*Test stride* in the *Test configuration* block of `run.log`).

## Tests

Each file groups related tests; nested class names are the JCStress selectors.

| Group | Tests | Subject |
| --- | ---: | --- |
| [CoRRMemoryModelTest](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/CoRRMemoryModelTest.java) | 64 | Write/read/read access-mode matrix |
| [PlainAccessMemoryModelTest](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/PlainAccessMemoryModelTest.java) | 34 | Plain coherence, mixed modes, CSE, RMWs and load buffering |
| [PublicationTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/PublicationTests.java) | 59 | Final fields, also read through VarHandles in every access mode, and unsafe publication |
| [SkipListPublicationTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/SkipListPublicationTests.java) | 16 | Node publication, key-dependent value reads and deletion-marker forwarding |
| [VolatileAccessesMemoryModelTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/VolatileAccessesMemoryModelTests.java) | 14 | Volatile accesses and fences |
| [MixedAccessMemoryModelTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/MixedAccessMemoryModelTests.java) | 4 | Release chains and volatile/plain access combinations |
| [JamMemoryModelTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/JamMemoryModelTests.java) | 64 | JAM19 examples ([4](#references)) |
| [MansonCausalityTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/MansonCausalityTests.java) | 20 | Java causality cases ([1](#references), [7](#references)) |
| [MansonThesisMemoryModelTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/MansonThesisMemoryModelTests.java) | 10 | Manson thesis examples ([2](#references)) |
| [SevcikAspinallMemoryModelTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/SevcikAspinallMemoryModelTests.java) | 3 | Transformation examples ([3](#references)) |
| [JcstressSampleMemoryModelTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/JcstressSampleMemoryModelTests.java) | 19 | OpenJDK JCStress examples ([8](#references)) |
| [CompilingVolatileMemoryModelTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/CompilingVolatileMemoryModelTests.java) | 4 | JAM21 examples ([5](#references)) |
| [JmtMemoryModelTests](litmus-tests/src/main/java/org/openjdk/jcstress/tests/jmm/JmtMemoryModelTests.java) | 29 | jMT collection ([6](#references)) |
| **Total** | **340** | |

## References

1. Jeremy Manson, William Pugh and Sarita V. Adve.
   [The Java Memory Model](https://doi.org/10.1145/1040305.1040336).
   POPL, 2005.
2. Jeremy Manson.
   [The Java Memory Model](https://hdl.handle.net/1903/1949).
   PhD thesis, University of Maryland, 2004.
3. Jaroslav Ševčík and David Aspinall.
   [On Validity of Program Transformations in the Java Memory Model](https://doi.org/10.1007/978-3-540-70592-5_3).
   ECOOP, 2008.
4. John Bender and Jens Palsberg.
   [A Formalization of Java’s Concurrent Access Modes](https://doi.org/10.1145/3360568).
   PACMPL 3 (OOPSLA), 2019. Introduces JAM19.
5. Shuyang Liu, John Bender and Jens Palsberg.
   [Compiling Volatile Correctly in Java](https://doi.org/10.4230/LIPIcs.ECOOP.2022.6).
   ECOOP, 2022. Introduces JAM21.
6. Lukas Panneke and Heike Wehrheim.
   [jMT: Testing Correctness of Java Memory Models](https://doi.org/10.1007/978-3-032-22749-2_13).
   TACAS, 2026. [Extended version](https://arxiv.org/abs/2604.15978).
7. [Pugh’s causality test cases](https://www.cs.umd.edu/~pugh/java/memoryModel/CausalityTestCases.html).
8. [OpenJDK JCStress examples](https://github.com/openjdk/jcstress/tree/master/jcstress-samples/src/main/java/org/openjdk/jcstress/samples/jmm).

## Attribution and licenses

The harness is based on
[viliamDokov/jcstress-experiments](https://github.com/viliamDokov/jcstress-experiments/tree/a36365bfdb2ec320737d8ff12de2cc9c1c15738b)
and retains its GPLv2 with Classpath Exception and third-party notices.

Litmus tests are adapted from [Lincheck](https://github.com/JetBrains/lincheck/),
Java memory model papers and OpenJDK JCStress examples.
The SkipList cases reduce publication and deletion-marker patterns from `ConcurrentSkipListMap`.
Original copyright and license headers are retained.
The recording scripts and result exporter use MPL-2.0.

License texts are included in the source tree and jar:

- [MPL-2.0](litmus-tests/src/main/resources/META-INF/jmm-litmus/licenses/MPL-2.0.txt)
- [LGPL-3.0](litmus-tests/src/main/resources/META-INF/jmm-litmus/licenses/LGPL-3.txt)
- [GPL-3.0](litmus-tests/src/main/resources/META-INF/jmm-litmus/licenses/GPL-3.txt), incorporated by LGPL-3.0
