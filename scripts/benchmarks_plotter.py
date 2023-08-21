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

    def benchmark_scenarios_stats(self, benchmark_id: int):
        return self._data[str(benchmark_id)]["scenariosStatistics"]

    def benchmark_runtime(self, benchmark_id: int, unit: TimeUnit = TimeUnit.MILLI):
        return as_time_unit(self._data[str(benchmark_id)]["runningTimeNano"], unit)

    def benchmarks_runtime(self, unit: TimeUnit = TimeUnit.MILLI):
        return {bid: self.benchmark_runtime(bid, unit=unit) for bid in self.benchmark_ids()}

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

    def benchmark_average_runtime_by_scenario_params(self, benchmark_id: int, unit: TimeUnit = TimeUnit.MILLI):
        result = list()
        groups = groupby(
            self.benchmark_scenarios_stats(benchmark_id),
            lambda stats: (stats["threads"], stats["operations"])
        )
        for params, iterations in groups:
            iterations = list(iterations)
            runtime = np.sum(list(map(itemgetter("runningTimeNano"), iterations)))
            invocations = np.sum(list(map(itemgetter("invocationsCount"), iterations)))
            avg_runtime = as_time_unit(float(runtime) / invocations, unit)
            result.append((params, avg_runtime))
        return result


def runtime_plot(ax: plt.Axes, report: BenchmarksReport):
    width = 0.25
    multiplier = 0
    time_unit = TimeUnit.SEC
    max_runtime = max(report.benchmarks_runtime(unit=time_unit).values())
    x = np.arange(len(report.benchmarks_names()))
    for mode in report.benchmarks_modes():
        offset = width * multiplier
        data = report.benchmarks_runtime_with_mode(mode=mode, unit=time_unit).values()
        ax.bar(x + offset, data, width=width, label=mode)
        multiplier += 1
    ax.set_title("Benchmarks running time")
    ax.set_ylabel("time (s)")
    ax.set_ylim(0, round_up_to(max_runtime, multiplier=10))
    ax.set_xticks(x + width / 2, report.benchmarks_names(), rotation=45)
    ax.legend(loc="upper left", bbox_to_anchor=(1, 1), ncol=1)


def scenarios_average_runtime_plot(ax: plt.Axes, report: BenchmarksReport, benchmark_id: int):
    width = 0.25
    data = report.benchmark_average_runtime_by_scenario_params(benchmark_id, unit=TimeUnit.MILLI)
    data.sort(key=itemgetter(0))
    scenario_params = list(map(itemgetter(0), data))
    runtimes = list(map(itemgetter(1), data))
    x = np.arange(len(data))
    ax.bar(x, runtimes, width=width)
    ax.set_title("Invocation average running time by scenario size")
    ax.set_ylabel("time (ms)")
    ax.set_xlabel("(#threads, #operations)")
    ax.set_xticks(x, scenario_params)


def round_up_to(n, multiplier):
    return math.ceil(n / multiplier) * multiplier


def main():
    with open("benchmarks-results.json", 'r') as json_file:
        data = json.load(json_file)
    report = BenchmarksReport(data)
    fig, ax = plt.subplots(nrows=2, ncols=1)
    fig.set_figwidth(6)
    fig.set_figheight(8)
    runtime_plot(ax[0], report)
    scenarios_average_runtime_plot(ax[1], report, benchmark_id=1)
    plt.tight_layout()
    plt.show()


if __name__ == "__main__":
    main()
