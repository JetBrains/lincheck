#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Report the hardware, OS and JDK a litmus-test run executes on, one `param: value` line each.

The processor and its presumed memory model come first, since the observable
outcomes of litmus tests depend on them more than on anything else.
Linux and macOS are supported; unavailable facts are reported as `unknown`.
"""
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Architecture-level memory models of the ISAs HotSpot runs on.
# These are inferred from the instruction set, not measured; see the `_note` line in the output.
MEMORY_MODELS = {
    ("x86_64", "amd64", "i386", "i686", "x86"):
        "x86-TSO (total store order; only store-load reordering through the store buffer)",
    ("aarch64", "arm64"):
        "Armv8-A (weakly ordered, multi-copy atomic; release/acquire and LSE atomics)",
    ("armv7l", "armv6l", "arm"):
        "Armv7-A (weakly ordered, not multi-copy atomic)",
    ("ppc64", "ppc64le", "powerpc"):
        "IBM Power (weakly ordered, not multi-copy atomic; cumulative barriers)",
    ("riscv64", "riscv32"):
        "RVWMO (RISC-V weak memory ordering, multi-copy atomic)",
    ("s390x", "s390"):
        "IBM z/Architecture (sequentially consistent for aligned accesses; TSO-like store buffering)",
    ("sparc64", "sparcv9", "sparc"):
        "SPARC TSO",
}

# CPU feature flags that bear on how the JIT emits concurrent code on each ISA.
INTERESTING_FLAGS = {
    "x86": ("sse2", "cx16", "clflush", "clflushopt", "avx", "avx2", "avx512f", "hle", "rtm", "hypervisor"),
    "arm": ("atomics", "lse", "lse2", "uscat", "ilrcpc", "lrcpc", "dcpop", "sb"),
    "riscv": ("ztso", "zacas", "zalrsc", "zaamo"),
}


def run(command, **kwargs):
    """Return the stdout of a command or None when it is missing or fails."""
    try:
        result = subprocess.run(command, capture_output=True, text=True, **kwargs)
    except (FileNotFoundError, PermissionError):
        return None
    return result.stdout if result.returncode == 0 else None


def read(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None


def gib(kib):
    return f"{kib / 1024 / 1024:.1f} GiB"


def os_release():
    text = read("/etc/os-release")
    if text:
        match = re.search(r'^PRETTY_NAME="?([^"\n]*)"?', text, re.M)
        if match:
            return match.group(1)
    if sys.platform == "darwin":
        name, version = run(["sw_vers", "-productName"]), run(["sw_vers", "-productVersion"])
        if name and version:
            return f"{name.strip()} {version.strip()}"
    return platform.platform()


def lscpu_fields():
    text = run(["lscpu"], env={**os.environ, "LC_ALL": "C"})
    fields = {}
    for line in (text or "").splitlines():
        key, sep, value = line.partition(":")
        if sep:
            fields[key.strip()] = value.strip()
    return fields


def proc_cpuinfo():
    text = read("/proc/cpuinfo") or ""
    fields = {}
    for line in text.splitlines():
        key, sep, value = line.partition(":")
        if sep and key.strip() not in fields:
            fields[key.strip()] = value.strip()
    return fields


def sysctl(name):
    value = run(["sysctl", "-n", name])
    return value.strip() if value else None


def cpu_info(info):
    machine = platform.machine()
    lscpu, cpuinfo = lscpu_fields(), proc_cpuinfo()
    logical = os.cpu_count()
    info["architecture"] = machine
    if sys.platform == "darwin":
        info["cpu_model"] = sysctl("machdep.cpu.brand_string")
        info["cpu_vendor"] = sysctl("machdep.cpu.vendor") or ("Apple" if machine == "arm64" else None)
        info["cpu_sockets"] = sysctl("hw.packages")
        info["cpu_cores"] = sysctl("hw.physicalcpu")
        info["cpu_logical"] = sysctl("hw.logicalcpu")
        if sysctl("hw.nperflevels") not in (None, "1"):
            info["cpu_performance_cores"] = sysctl("hw.perflevel0.physicalcpu")
            info["cpu_efficiency_cores"] = sysctl("hw.perflevel1.physicalcpu")
        frequency = sysctl("hw.cpufrequency_max")
        info["cpu_max_mhz"] = str(int(frequency) // 1_000_000) if frequency else None
        caches = [(name, sysctl(f"hw.{key}")) for name, key in
                  (("L1d", "l1dcachesize"), ("L1i", "l1icachesize"), ("L2", "l2cachesize"), ("L3", "l3cachesize"))]
        info["cpu_caches"] = ", ".join(f"{name} {int(size) // 1024} KiB" for name, size in caches if size) or None
        translated = sysctl("sysctl.proc_translated")
        if translated == "1":
            info["cpu_translation"] = "Rosetta 2 (x86_64 binaries on Apple silicon)"
        flags = (sysctl("machdep.cpu.features") or "").lower().split()
    else:
        info["cpu_model"] = lscpu.get("Model name") or cpuinfo.get("model name") or cpuinfo.get("cpu") \
            or cpuinfo.get("uarch") or platform.processor() or None
        info["cpu_vendor"] = lscpu.get("Vendor ID") or cpuinfo.get("vendor_id") or cpuinfo.get("CPU implementer")
        info["cpu_family_model_stepping"] = ", ".join(
            f"{key.lower()} {lscpu[key]}" for key in ("CPU family", "Model", "Stepping") if key in lscpu) or None
        info["cpu_sockets"] = lscpu.get("Socket(s)")
        cores = lscpu.get("Core(s) per socket")
        if cores and lscpu.get("Socket(s)", "").isdigit():
            info["cpu_cores"] = str(int(cores) * int(lscpu["Socket(s)"]))
        info["cpu_cores_per_socket"] = cores
        info["cpu_threads_per_core"] = lscpu.get("Thread(s) per core")
        info["cpu_logical"] = lscpu.get("CPU(s)") or (str(logical) if logical else None)
        info["cpu_online"] = lscpu.get("On-line CPU(s) list")
        info["cpu_numa_nodes"] = lscpu.get("NUMA node(s)")
        info["cpu_max_mhz"] = lscpu.get("CPU max MHz")
        info["cpu_min_mhz"] = lscpu.get("CPU min MHz")
        info["cpu_current_mhz"] = cpuinfo.get("cpu MHz")
        caches = [(name, lscpu.get(f"{name} cache")) for name in ("L1d", "L1i", "L2", "L3")]
        info["cpu_caches"] = ", ".join(f"{name} {size}" for name, size in caches if size) or None
        smt = read("/sys/devices/system/cpu/smt/active")
        info["cpu_smt_active"] = {"1": "yes", "0": "no"}.get(smt, None)
        info["cpu_frequency_governor"] = read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        info["virtualization"] = lscpu.get("Hypervisor vendor") and \
            f"{lscpu['Hypervisor vendor']} ({lscpu.get('Virtualization type', 'unknown type')})"
        flags = (lscpu.get("Flags") or cpuinfo.get("flags") or cpuinfo.get("Features") or cpuinfo.get("isa") or "") \
            .lower().replace("_", "").split()
    family = "x86" if machine in ("x86_64", "amd64", "i386", "i686") \
        else "arm" if machine.startswith(("aarch64", "arm")) else "riscv" if machine.startswith("riscv") else None
    interesting = INTERESTING_FLAGS.get(family, ())
    present = [flag for flag in interesting if flag in flags]
    if interesting and flags:
        info["cpu_flags_relevant"] = " ".join(present) or "none of: " + " ".join(interesting)
    if info.get("virtualization") is None and "hypervisor" in flags:
        info["virtualization"] = "hypervisor flag present (vendor unknown)"
    model = next((text for names, text in MEMORY_MODELS.items() if machine in names), None)
    if machine.startswith("riscv") and "ztso" in flags:
        model = "RVTSO (RISC-V with the Ztso extension; total store order)"
    if model and info.get("cpu_translation"):
        model = "x86-TSO emulated by Rosetta 2 on Armv8-A hardware"
    info["memory_model_presumed"] = model or f"unknown for architecture {machine}"
    info["memory_model_note"] = "inferred from the instruction-set architecture, not measured"


def container():
    if Path("/.dockerenv").exists():
        return "Docker"
    if Path("/run/.containerenv").exists():
        return "Podman"
    cgroup = read("/proc/1/cgroup") or ""
    if "kubepods" in cgroup:
        return "Kubernetes"
    if "docker" in cgroup or "containerd" in cgroup:
        return "container (cgroup)"
    return "no"


def memory_info(info):
    meminfo = read("/proc/meminfo")
    if meminfo:
        fields = dict(re.findall(r"^(\w+):\s+(\d+) kB", meminfo, re.M))
        info["memory_total"] = gib(int(fields["MemTotal"])) if "MemTotal" in fields else None
        info["memory_available"] = gib(int(fields["MemAvailable"])) if "MemAvailable" in fields else None
        info["swap_total"] = gib(int(fields["SwapTotal"])) if "SwapTotal" in fields else None
    elif sys.platform == "darwin":
        size = sysctl("hw.memsize")
        info["memory_total"] = gib(int(size) / 1024) if size else None
    limit = read("/sys/fs/cgroup/memory.max") or read("/sys/fs/cgroup/memory/memory.limit_in_bytes")
    if limit and limit.isdigit() and int(limit) < 1 << 60:
        info["memory_cgroup_limit"] = gib(int(limit) / 1024)
    quota = read("/sys/fs/cgroup/cpu.max")
    if quota and not quota.startswith("max"):
        used, period = quota.split()
        info["cpu_cgroup_quota"] = f"{int(used) / int(period):.2f} CPUs"


def java_info(info, java):
    resolved = shutil.which(java) or java
    try:
        # The settings go to stderr, together with the version banner.
        text = subprocess.run([resolved, "-XshowSettings:properties", "-version"], capture_output=True, text=True,
                              env=clean_java_env()).stderr
    except (FileNotFoundError, PermissionError):
        text = ""
    properties = dict(re.findall(r"^\s*(java\.[\w.]+|os\.\w+|sun\.arch\.data\.model) = (.*)$", text, re.M))
    if not properties:
        info["java"] = f"unknown ({java} not found or not a JDK)"
        return
    info["java_version"] = properties.get("java.version")
    info["java_runtime"] = f"{properties.get('java.runtime.name')} {properties.get('java.runtime.version')}"
    info["java_vendor"] = properties.get("java.vendor.version") or properties.get("java.vendor")
    info["java_vm"] = f"{properties.get('java.vm.name')} {properties.get('java.vm.version')}"
    info["java_home"] = properties.get("java.home")
    info["java_os_arch"] = properties.get("os.arch")
    flags = run([resolved, "-XX:+PrintFlagsFinal", "-version"], env=clean_java_env()) or ""
    values = dict(re.findall(r"^\s*\w+ (\w+)\s+:?= (\S+)", flags, re.M))
    wanted = ["TieredCompilation", "CICompilerCount", "UseCompressedOops", "MaxHeapSize", "ActiveProcessorCount",
              "UseContainerSupport", "UseAVX", "UseSSE", "UseLSE", "UseBarriersForVolatile", "UseNUMA"]
    collectors = [name for name in ("UseG1GC", "UseParallelGC", "UseSerialGC", "UseZGC", "UseShenandoahGC")
                  if values.get(name) == "true"]
    info["java_default_flags"] = ", ".join(f"{name}={values[name]}" for name in wanted if name in values) or None
    info["java_default_gc"] = ", ".join(collectors) or None


def clean_java_env():
    # JAVA_TOOL_OPTIONS makes java print a "Picked up" banner on stderr; drop it so parsing stays clean.
    return {key: value for key, value in os.environ.items() if key != "JAVA_TOOL_OPTIONS"}


def harness_info(info, jar):
    pom = read(HERE.parent / "pom.xml") or ""
    version = re.search(r"<artifactId>jcstress-parent</artifactId>\s*<version>([^<]+)</version>", pom)
    info["jcstress_version"] = (version.group(1) + " (this repository's fork)") if version else None
    manifest = read(HERE / "src/main/resources/META-INF/jmm-litmus/manifest.json")
    if manifest:
        info["litmus_tests"] = str(len(json.loads(manifest)))
    if jar.is_file():
        info["jar"] = str(jar)
        info["jar_sha256"] = hashlib.sha256(jar.read_bytes()).hexdigest()
        try:
            with zipfile.ZipFile(jar) as archive:
                embedded = json.loads(archive.read("META-INF/jmm-litmus/manifest.json"))
                info["jar_litmus_tests"] = str(len(embedded))
        except (KeyError, zipfile.BadZipFile, json.JSONDecodeError):
            info["jar_litmus_tests"] = "no valid embedded manifest"
    else:
        info["jar"] = f"not built ({jar})"
    revision = run(["git", "rev-parse", "HEAD"], cwd=HERE)
    info["git_revision"] = revision.strip() if revision else None
    status = run(["git", "status", "--porcelain"], cwd=HERE)
    info["git_working_tree"] = ("dirty" if status.strip() else "clean") if status is not None else None
    maven = run(["mvn", "-v"], env=clean_java_env())
    info["maven_version"] = maven.splitlines()[0].strip() if maven else None
    info["python_version"] = platform.python_version()


def collect(java, jar):
    info = {"report_utc": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
            "hostname": socket.gethostname(),
            "os": os_release(),
            "kernel": f"{platform.system()} {platform.release()}"}
    cpu_info(info)
    info["container"] = container()
    memory_info(info)
    try:
        info["load_average"] = ", ".join(f"{load:.2f}" for load in os.getloadavg())
    except OSError:
        pass
    java_info(info, java)
    harness_info(info, jar)
    # Facts the paper cannot do without stay visible even when unavailable; optional ones are dropped.
    required = ("os", "architecture", "cpu_model", "cpu_cores", "cpu_logical", "memory_total", "java_version")
    return {key: value if value not in (None, "") else "unknown" for key, value in info.items()
            if value not in (None, "") or key in required}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--java", default="java", help="JDK whose settings to report (default: java on PATH)")
    parser.add_argument("--jar", type=Path, default=HERE / "target/jcstress.jar")
    parser.add_argument("--output", type=Path, help="Also write the report to this file")
    parser.add_argument("--json", action="store_true", help="Print a JSON object instead of `param: value` lines")
    args = parser.parse_args()
    info = collect(args.java, args.jar.resolve())
    text = json.dumps(info, indent=2) + "\n" if args.json else "".join(f"{k}: {v}\n" for k, v in info.items())
    sys.stdout.write(text)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)


if __name__ == "__main__":
    main()
