#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Run JCStress and save the jar, results, environment and system specification."""
import argparse
import hashlib
import json
import platform
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument("--java", default="java")
parser.add_argument("--jar", type=Path, default=HERE / "target/jcstress.jar")
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--tests", default="org.openjdk.jcstress.tests.jmm.*")
parser.add_argument("--cpus", type=int)
parser.add_argument("--budget", default="1h", help="Total harness budget, e.g. 10m, 1h or 0s for sanity only")
parser.add_argument("--split-compilation", choices=("true", "false"), default="true")
parser.add_argument("--forks", type=int, default=1)
parser.add_argument("--affinity", choices=("NONE", "LOCAL", "GLOBAL"), default="LOCAL")
parser.add_argument("--jvm-args", help="Base JVM options instead of automatic presets")
args = parser.parse_args()
java = str(Path(shutil.which(args.java) or args.java).resolve())
output = args.output.resolve()
if output.exists() and any(output.iterdir()):
    parser.error("Use a new or empty output directory to avoid mixing experiments.")
output.mkdir(parents=True, exist_ok=True)
jar = args.jar.resolve()
if not jar.is_file():
    parser.error("Build the harness with mvn -pl litmus-tests -am package -Dmaven.javadoc.skip=true first.")
try:
    with zipfile.ZipFile(jar) as archive:
        embedded = {name: archive.read("META-INF/jmm-litmus/" + name)
                    for name in ("manifest.json",)}
        for content in embedded.values():
            json.loads(content)
except (KeyError, zipfile.BadZipFile, json.JSONDecodeError) as error:
    parser.error(f"The jar lacks a valid test manifest; rebuild it: {error}")
shutil.copyfile(jar, output / "jcstress.jar")
jar = output / "jcstress.jar"
command = [java, "-jar", str(jar), "-t", args.tests, "-tb", args.budget, "-f", str(args.forks),
           "-sc", args.split_compilation, "-af", args.affinity, "-r", "html"]
if args.cpus is not None:
    command += ["-c", str(args.cpus)]
if args.jvm_args is not None:
    command += ["-jvmArgs", args.jvm_args]
# Ask Java to apply its own regular-expression syntax and test-name matching semantics.
listing = subprocess.run([java, "-jar", str(jar), "-l", "-t", args.tests],
                         capture_output=True, text=True, check=True)
manifest = json.loads(embedded["manifest.json"])
known = {case["jcstress"] for case in manifest}
selected = known.intersection(line.strip() for line in listing.stdout.splitlines())
if not selected:
    parser.error("The test selector matched no cases from the embedded corpus manifest.")
(output / "selected-tests.txt").write_text("\n".join(sorted(selected)) + "\n")

def capture(command):
    try:
        result = subprocess.run(command, cwd=HERE, capture_output=True, text=True)
        return (result.stdout + result.stderr).strip() if result.returncode == 0 else None
    except FileNotFoundError:
        return None

metadata = {"started_utc": datetime.now(timezone.utc).isoformat(), "command": command,
            "platform": platform.platform(), "machine": platform.machine(),
            "java": subprocess.run([java, "-version"], capture_output=True, text=True).stderr,
            "revision": capture(["git", "rev-parse", "HEAD"]),
            "git_status": capture(["git", "status", "--porcelain"]),
            "selected_tests": sorted(selected),
            "test_count": len(manifest),
            "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "cpu": capture(["lscpu"])}
for name, content in embedded.items():
    (output / name).write_bytes(content)
(output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
subprocess.run([sys.executable, str(HERE / "system_info.py"), "--java", java, "--jar", str(jar),
                "--output", str(output / "system.txt")], check=True, stdout=subprocess.DEVNULL)
with (output / "run.log").open("w") as log:
    result = subprocess.run(command, cwd=output, stdout=log, stderr=subprocess.STDOUT)
metadata["finished_utc"] = datetime.now(timezone.utc).isoformat()
metadata["exit_code"] = result.returncode
(output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
blobs = sorted(output.glob("jcstress-results-*.bin.gz"))
if blobs:
    with (output / "raw.jsonl").open("w") as raw:
        subprocess.run([java, "-cp", str(jar), "org.openjdk.jcstress.tests.jmm.tools.DumpResults",
                        *map(str, blobs)], stdout=raw, check=True)
    subprocess.run([sys.executable, str(HERE / "export_results.py"), str(output / "raw.jsonl"),
                    str(output / "results.json")], check=True)
    exported = json.loads((output / "results.json").read_text())
    metadata["missing_tests"] = sorted(selected - exported.keys())
    metadata["failed_configurations"] = {
        name: case["statuses"] for name, case in exported.items() if case["statuses"] != ["NORMAL"] or case["samples"] == 0
    }
    (output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
incomplete = bool(metadata.get("missing_tests") or metadata.get("failed_configurations") or not blobs)
print(f"JCStress exited {result.returncode}; incomplete={incomplete}; evidence saved to {output}")
sys.exit(result.returncode or int(incomplete))
