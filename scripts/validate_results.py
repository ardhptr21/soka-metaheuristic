#!/usr/bin/env python3
import csv
import os
from pathlib import Path

root = Path(__file__).resolve().parents[1]
with (root / "results" / "runs.csv").open(newline="") as f:
    rows = list(csv.DictReader(f))

selected = os.environ.get("META_ALGORITHM", "ALL").upper()
algorithms = {"SMA", "AO"} if selected == "ALL" else set(selected.split(","))
expected_runs = sum(15 for _ in algorithms)
assert len(rows) == expected_runs, (len(rows), expected_runs)
assert {r["algorithm"] for r in rows} == algorithms
assert {int(r["workload_size"]) for r in rows} == set(range(1000, 5001, 1000))
assert all(int(r["end_row"]) > int(r["start_row"]) for r in rows)
assert all(int(r["workload_size"]) == int(r["end_row"]) - int(r["start_row"]) + 1 for r in rows)
assert all(int(r["start_row"]) >= 1 and int(r["end_row"]) <= 45000 for r in rows)
for algorithm in algorithms:
    selected_rows = [r for r in rows if r["algorithm"] == algorithm]
    assert len(selected_rows) == 15
    assert {int(r["workload_size"]) for r in selected_rows} == set(range(1000, 5001, 1000))
    ranges = {(r["start_row"], r["end_row"]) for r in selected_rows}
    assert len(ranges) == 15, (algorithm, "duplicate workload ranges")
for r in rows:
    for field in ("makespan", "total_cost", "cpu_utilization", "throughput", "degree_imbalance", "deadline_miss_ratio", "budget"):
        assert float(r[field]) >= 0, (r, field)
print(f"Validated {len(rows)} runs for {', '.join(sorted(algorithms))}; all ranges are unique and metrics are non-negative.")
