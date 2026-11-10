#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Aggregate raw per-configuration JCStress JSON lines, retaining every failure status."""
import argparse
import json
from collections import Counter
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("raw", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
results = {}
for line in args.raw.read_text().splitlines():
    raw = json.loads(line)
    item = results.setdefault(raw["name"], {"outcomes": Counter(), "samples": 0, "statuses": [], "configs": 0})
    item["outcomes"].update(raw["outcomes"])
    item["samples"] += sum(raw["outcomes"].values())
    item["configs"] += 1
    if raw["status"] not in item["statuses"]:
        item["statuses"].append(raw["status"])
args.output.write_text(json.dumps(results, indent=2, sort_keys=True) + "\n")
print(f"Exported {len(results)} tests; {sum(item['samples'] for item in results.values()):,} samples.")
