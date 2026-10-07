#!/usr/bin/env python3
import argparse
import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "results"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--algorithm", default="ALL")
    selected = parser.parse_args().algorithm.upper()
    algorithms = ["SMA", "AO"] if selected == "ALL" else selected.split(",")
    with (RESULTS / "summary.csv").open(newline="") as f:
        rows = list(csv.DictReader(f))
    sizes = list(range(1000, 5001, 1000))
    labels = {"SMA": "Slime Mould Algorithm", "AO": "Aquila Optimizer"}
    colors = {"SMA": "#4472C4", "AO": "#ED7D31"}

    fig, axes = plt.subplots(3, 2, figsize=(13, 12), constrained_layout=True)
    metrics = [("makespan_mean", "Makespan"), ("cost_mean", "Total cost"), ("cpu_util_mean", "CPU utilization"), ("throughput_mean", "Throughput"), ("imbalance_mean", "Load imbalance"), ("deadline_miss_mean", "Deadline miss ratio")]
    for ax, (field, title) in zip(axes.flat, metrics):
        for algorithm in algorithms:
            values = [float(next(r for r in rows if r["algorithm"] == algorithm and int(r["workload_size"]) == size)[field]) for size in sizes]
            ax.plot(sizes, values, marker="o", label=labels[algorithm], color=colors[algorithm])
        ax.set_title(title)
        ax.set_xlabel("Workload size (cloudlets)")
        ax.grid(alpha=0.25)
        ax.legend()
    fig.suptitle("SMA versus AO Scheduling Results")
    fig.savefig(RESULTS / "comparison.png", dpi=180)
    plt.close(fig)

    overall = [r for r in rows if int(r["workload_size"]) == 0]
    fig, ax = plt.subplots(figsize=(8, 5), constrained_layout=True)
    fields = [("makespan_mean", "Makespan"), ("cost_mean", "Cost"), ("deadline_miss_mean", "Deadline miss")]
    x = range(len(fields))
    width = 0.8 / max(1, len(algorithms))
    for index, algorithm in enumerate(algorithms):
        row = next(r for r in overall if r["algorithm"] == algorithm)
        ax.bar([i + index * width for i in x], [float(row[field]) for field, _ in fields], width, label=labels[algorithm], color=colors[algorithm])
    ax.set_xticks([i + width * (len(algorithms) - 1) / 2 for i in x], [title for _, title in fields])
    ax.set_title("Overall aggregate across all tested workload chunks")
    ax.legend()
    fig.savefig(RESULTS / "overall.png", dpi=180)
    plt.close(fig)
    print("Wrote comparison.png and overall.png")

if __name__ == "__main__":
    main()
