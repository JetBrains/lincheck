#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Check source hashes, test counts and the built JCStress test registry."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--java", default="java")
parser.add_argument("--jar", type=Path, default=HERE / "target/jcstress.jar")
args = parser.parse_args()
manifest_bytes = (HERE / "src/main/resources/META-INF/jmm-litmus/manifest.json").read_bytes()
manifest = json.loads(manifest_bytes)
names = {c["jcstress"] for c in manifest}
keys = {c["id"] for c in manifest}
assert len(manifest) == len(names) == len(keys) == 340, "Incomplete or duplicate corpus entries"
expected_counts = {
    "CoRRMemoryModelTest": 64, "PlainAccessMemoryModelTest": 34, "PublicationTests": 59,
    "VolatileAccessesMemoryModelTests": 14, "JamMemoryModelTests": 64, "MansonCausalityTests": 20,
    "MansonThesisMemoryModelTests": 10, "SevcikAspinallMemoryModelTests": 3,
    "JcstressSampleMemoryModelTests": 19, "CompilingVolatileMemoryModelTests": 4,
    "JmtMemoryModelTests": 29, "MixedAccessMemoryModelTests": 4, "SkipListPublicationTests": 16,
}
assert Counter(c["group"] for c in manifest) == expected_counts, "Corpus group counts changed"
source_tests = set()
for relative in sorted({c["source"] for c in manifest}):
    path = HERE / relative
    source = path.read_text()
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    cases = [c for c in manifest if c["source"] == relative]
    for case in cases:
        assert digest == case["source_sha256"], f"Stale source hash: {relative}"
        assert len(case["outcome_components"]) == len(case["result_type"].removesuffix("_Result"))
    tests = re.findall(r"@JCStressTest\s+@State\s+(?:@Outcome[^\n]*\n\s*)+public static class (\w+)", source)
    source_tests.update("org.openjdk.jcstress.tests.jmm." + path.stem + "." + name for name in tests)
    assert source.count("@Actor") == sum(c["actors"] for c in cases), f"Actor count: {relative}"
assert source_tests == names, "Source classes and manifest differ"
with zipfile.ZipFile(args.jar) as jar:
    assert jar.read("META-INF/jmm-litmus/manifest.json") == manifest_bytes, "Rebuild: jar manifest is stale"
    assert not any(n.startswith(("kotlin/", "org/jetbrains/kotlinx/lincheck/")) for n in jar.namelist())
listing = subprocess.run([args.java, "-jar", str(args.jar), "-l"],
                         check=True, capture_output=True, text=True)
registered = {line.strip() for line in listing.stdout.splitlines()
              if line.strip().startswith("org.openjdk.jcstress.tests.")}
assert registered == names, f"Registry mismatch: missing={names - registered}, extra={registered - names}"
print(f"Verified {len(names)} tests in {len(expected_counts)} groups; jar and manifest match.")
