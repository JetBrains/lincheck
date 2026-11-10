#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Summarise the set-up and extent of a recorded run, one `param: value` line each.

Reads a results directory written by `run.py` and reports what an experimental-setup
paragraph needs: the machine (from `system.txt`), the harness parameters and JVM
configurations (from `run.log` and `environment.json`), and the volume of evidence
(from `raw.jsonl` and `results.json`).
"""
import argparse
import json
import re
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

# Lines from system.txt that describe the machine; the rest of that file is build provenance.
MACHINE_KEYS = ("hostname", "os", "kernel", "architecture", "cpu_model", "cpu_vendor", "cpu_sockets", "cpu_cores",
                "cpu_threads_per_core", "cpu_logical", "cpu_max_mhz", "cpu_current_mhz", "cpu_caches", "cpu_numa_nodes",
                "cpu_smt_active", "cpu_frequency_governor", "virtualization", "container", "cpu_translation",
                "memory_model_presumed", "memory_model_note", "memory_total", "memory_cgroup_limit", "cpu_cgroup_quota",
                "java_version", "java_vendor", "java_vm", "java_default_flags", "java_default_gc",
                "jcstress_version", "jar_sha256", "git_revision", "git_working_tree")


def read_text(path):
    return path.read_text() if path.is_file() else ""


def read_json(path):
    return json.loads(path.read_text()) if path.is_file() else {}


def parse_time(value):
    return datetime.fromisoformat(value) if value else None


def duration(seconds):
    hours, rest = divmod(int(seconds), 3600)
    minutes, secs = divmod(rest, 60)
    return f"{seconds:.0f} s ({hours}h {minutes:02d}m {secs:02d}s)"


def harness_options(command):
    """Map JCStress options from the recorded command line to readable names."""
    names = {"-t": "test_selector", "-tb": "time_budget", "-f": "forks_per_test", "-c": "cpus_requested",
             "-sc": "split_compilation", "-af": "affinity_mode", "-jvmArgs": "base_jvm_args", "-r": "report_dir"}
    options = {}
    for index, token in enumerate(command):
        if token in names and index + 1 < len(command):
            options[names[token]] = command[index + 1]
    return options


def log_facts(log):
    facts = {}
    match = re.search(r"^Rev: (.*)$", log, re.M)
    if match:
        facts["jcstress_build"] = match.group(1)
    match = re.search(r"^\s*(\d+ \w+?s?, \d+ cores? per \w+, \d+ threads? per core)$", log, re.M)
    if match:
        facts["topology_detected"] = match.group(1)
    for label, key in (("Hardware CPUs in use", "cpus_in_use"), ("Spinning style", "spinning_style"),
                       ("Forks per test", "forks"), ("Test stride", "test_stride")):
        match = re.search(rf"^\s*{label}: (.*)$", log, re.M)
        if match:
            facts[key] = match.group(1)
    match = re.search(r"^\s*Initial test time: (\d+) ms$", log, re.M)
    if match:
        facts["initial_time_per_configuration"] = f"{match.group(1)} ms"
    elif "Zero budget, sanity test mode" in log:
        facts["time_budget_mode"] = "zero budget, sanity test mode"
    section = re.search(r"Probing what VM configurations are available:(.*?)\n\n(?=[^-\n])", log, re.S)
    if section:
        available = re.findall(r"^----- \[OK\] (\[.*\])$", section.group(1), re.M)
        unavailable = re.findall(r"^----- \[N/A\] (\[.*\])$", section.group(1), re.M)
        facts["jvm_configurations"] = f"{len(available)}: " + "; ".join(available)
        if unavailable:
            facts["jvm_configurations_unavailable"] = "; ".join(unavailable)
    classes = re.findall(r"^\s*(\d+) actors:\n((?:^\s{6}.*\n)+)", log, re.M)
    if classes:
        facts["scheduling_classes"] = "; ".join(f"{actors} actors: {len(body.strip().splitlines())}"
                                                for actors, body in classes)
    return facts


def evidence(raw_path, results):
    facts = {}
    if raw_path.is_file():
        records = [json.loads(line) for line in raw_path.read_text().splitlines() if line.strip()]
        facts["configurations_run"] = str(len(records))
        # Every C2 stress configuration gets its own random seed; count the flag sets, not the seeds.
        facts["jvm_args_variants"] = str(len({re.sub(r"StressSeed=\d+", "StressSeed=*", record["jvm_args"])
                                              for record in records}))
        modes = Counter("unified" if record["compile_mode"] == -1 else "split" for record in records)
        facts["compile_modes"] = ", ".join(f"{mode} {count}" for mode, count in sorted(modes.items()))
        statuses = Counter(record["status"] for record in records)
        facts["configuration_statuses"] = ", ".join(f"{status} {count}" for status, count in sorted(statuses.items()))
    if results:
        samples = [case["samples"] for case in results.values()]
        facts["tests_with_results"] = str(len(results))
        facts["total_samples"] = f"{sum(samples):,}"
        facts["samples_per_test"] = f"min {min(samples):,}, median {sorted(samples)[len(samples) // 2]:,}, max {max(samples):,}"
        facts["tests_with_interesting_outcomes"] = str(sum(
            1 for case in results.values() if len(case["outcomes"]) > 1))
    return facts


def summarise(directory):
    environment = read_json(directory / "environment.json")
    system = dict(line.split(": ", 1) for line in read_text(directory / "system.txt").splitlines() if ": " in line)
    log = read_text(directory / "run.log")
    results = read_json(directory / "results.json")
    summary = {"results_dir": str(directory)}
    summary.update((key, system[key]) for key in MACHINE_KEYS if key in system)
    if not system:
        summary["system"] = "system.txt missing; the run predates system_info.py (see environment.json)"
        summary["platform"] = environment.get("platform", "unknown")
    started, finished = parse_time(environment.get("started_utc")), parse_time(environment.get("finished_utc"))
    if started:
        summary["started_utc"] = started.strftime("%Y-%m-%d %H:%M:%S")
    if started and finished:
        summary["elapsed"] = duration((finished - started).total_seconds())
    if "exit_code" in environment:
        summary["harness_exit_code"] = str(environment["exit_code"])
    summary.update(harness_options(environment.get("command", [])))
    summary["tests_selected"] = str(len(environment.get("selected_tests", [])))
    summary["tests_in_corpus"] = str(environment.get("test_count", "unknown"))
    summary.update(log_facts(log))
    summary.update(evidence(directory / "raw.jsonl", results))
    for key in ("missing_tests", "failed_configurations"):
        if environment.get(key):
            summary[key] = str(len(environment[key]))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("results", type=Path, help="Results directory written by run.py")
    parser.add_argument("--json", action="store_true", help="Print a JSON object instead of `param: value` lines")
    args = parser.parse_args()
    if not (args.results / "environment.json").is_file():
        parser.error(f"{args.results} has no environment.json; pass a directory written by run.py")
    summary = summarise(args.results.resolve())
    if args.json:
        print(json.dumps(summary, indent=2))
    else:
        sys.stdout.write("".join(f"{key}: {value}\n" for key, value in summary.items()))


if __name__ == "__main__":
    main()
