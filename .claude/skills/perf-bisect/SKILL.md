---
name: perf-bisect
description: Bisects performance degradation by splitting a change (commit, diff, or local modifications) into small meaningful patches, applying them one-by-one, and benchmarking after each to find the culprit.
---

# Performance Bisection

You are investigating a performance degradation caused by a single change — this could be one commit, a local diff, or uncommitted modifications. Your goal is to find the **smallest sub-change** that causes the regression.

## Strategy

1. **Identify the input**: the commit, diff, or local change to bisect, and the benchmark/test to run. Ask the user if not clear from `$ARGUMENTS`.
2. **Understand the full change**: read the diff carefully and identify logically independent sub-changes (e.g., a new field, a refactored method, an added branch, a changed constant).
3. **Split into small meaningful patches**: decompose the change into a sequence of patches that can be applied incrementally. Each patch must:
   - Be a coherent, logically meaningful unit (not arbitrary line splits)
   - Compile and pass basic sanity when applied on top of previous patches
   - Rough edges are OK — the goal is bisection, not polish
4. **Start from baseline**: revert the full change (or check out the parent commit) so you have a clean starting point with known-good performance.
5. **Apply patches one-by-one**: after each patch, run the benchmark and record the result.
6. **Identify the culprit**: the patch after which performance degrades is the culprit. If the degradation is gradual across multiple patches, report all contributing patches.

## Running benchmarks

- Ask the user how to run the relevant benchmark if not obvious from context.
- Compare results against the baseline (before any patches).
- A degradation is significant if it exceeds ~10% regression or the user-specified threshold.
- Run each benchmark multiple times if results are noisy.

## Output

After bisection, report:
- **Culprit patch**: the specific sub-change (show the diff)
- **Baseline performance**: numbers before the patch
- **Degraded performance**: numbers after the patch
- **Suggested fix or investigation direction**
