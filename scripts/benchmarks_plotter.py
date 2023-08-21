#  Lincheck
#
#  Copyright (C) 2019 - 2023 JetBrains s.r.o.
#
#  This Source Code Form is subject to the terms of the
#  Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
#  with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
import math

import matplotlib.pyplot as plt
import numpy as np
import json

from enum import Enum
from itertools import groupby
from operator import itemgetter


class TimeUnit(Enum):
    NANO = 1
    MICRO = 1_000
    MILLI = 1_000_000
    SEC = 1_000_000_000


def as_time_unit(nano_time: int, unit: TimeUnit):
    return float(nano_time) / unit.value


class BenchmarksReport:

    def __init__(self, data):
        self._data = data
        self._names = sorted(set([self.benchmark_name(bid) for bid in self.benchmark_ids()]))
        self._modes = sorted(set([self.benchmark_mode(bid) for bid in self.benchmark_ids()]))

    def benchmark_ids(self):
        return self._data.keys()

    def benchmarks_names(self):
        return self._names

    def benchmarks_modes(self):
        return self._modes

    def benchmark_name(self, benchmark_id: int):
        return self._data[str(benchmark_id)]["name"].replace("Benchmark", "")

    def benchmark_mode(self, benchmark_id: int):
        return self._data[str(benchmark_id)]["mode"]

    def benchmark_runtime(self, benchmark_id: int, unit: TimeUnit = TimeUnit.MILLI):
        return as_time_unit(self._data[str(benchmark_id)]["runningTimeNano"], unit)

    def benchmarks_runtime(self, unit: TimeUnit = TimeUnit.MILLI):
        return [self.benchmark_runtime(bid, unit=unit) for bid in self.benchmark_ids()]

    def benchmarks_runtime_grouped_by_name(self, unit: TimeUnit = TimeUnit.MILLI):
        result = dict()
        for name, benchmarks in groupby(self._data.values(), key=itemgetter("name")):
            benchmarks_ids = list(map(itemgetter("id"), benchmarks))
            result[name] = \
                {self.benchmark_mode(bid): self.benchmark_runtime(bid, unit=unit) for bid in benchmarks_ids}
        return result

    def benchmarks_runtime_with_mode(self, mode: str, unit: TimeUnit = TimeUnit.MILLI):
        result = dict()
        for bid in self.benchmark_ids():
            if self.benchmark_mode(bid) != mode:
                continue
            result[self.benchmark_name(bid)] = self.benchmark_runtime(bid, unit=unit)
        return result

# def runtime_plot(ax: plt.Axes, benchmarks: List[str], runtime: List[int]):
#     xs = np.arange(len(benchmarks))
#     ax.bar(xs, runtime, align="center")
#     ax.set_xticks(xs, benchmarks)
#     ax.set_ylabel("running time")
#     ax.set_title("Benchmarks running time")

def runtime_plot(ax: plt.Axes, report: BenchmarksReport):
    width = 0.25
    multiplier = 0
    time_unit = TimeUnit.SEC
    max_runtime = max(report.benchmarks_runtime(unit=time_unit))
    x = np.arange(len(report.benchmarks_names()))
    for mode in report.benchmarks_modes():
        offset = width * multiplier
        data = report.benchmarks_runtime_with_mode(mode=mode, unit=time_unit).values()
        ax.bar(x + offset, data, width, label=mode)
        multiplier += 1
    ax.set_title("Benchmarks running time")
    ax.set_ylabel("time (s)")
    ax.set_ylim(0, round_up_to(max_runtime, multiplier=10))
    ax.set_xticks(x + width / 2, report.benchmarks_names(), rotation=45)
    ax.legend(loc="upper left", bbox_to_anchor=(1, 1), ncol=1)

def round_up_to(n, multiplier):
    return math.ceil(n / multiplier) * multiplier

def main():
    with open("benchmarks-results.json", 'r') as json_file:
        data = json.load(json_file)
    report = BenchmarksReport(data)
    runtime_plot(plt.axes(), report)
    plt.tight_layout()
    plt.show()



if __name__ == "__main__":
    main()
