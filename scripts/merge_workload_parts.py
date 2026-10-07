#!/usr/bin/env python3
import csv
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parts = sorted((root / "data" / "parts").glob("part_*.csv"))
if not parts:
    raise SystemExit("No downloaded workload parts found")

rows = []
seen = set()
for part in parts:
    with part.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            fingerprint = tuple(row[k] for k in ("start_time", "execution_duration", "cpu_request", "memory_request_mb"))
            if fingerprint in seen:
                raise SystemExit(f"Duplicate workload row found in {part}: {fingerprint}")
            seen.add(fingerprint)
            rows.append(row)

if len(rows) < 45_000:
    raise SystemExit(f"Only {len(rows)} unique rows available; need 45000")

output = root / "data" / "workload.csv"
with output.open("w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(["task_id", "start_time", "execution_duration", "cpu_request", "memory_request_mb"])
    for task_id, row in enumerate(rows[:45_000], 1):
        writer.writerow([task_id, row["start_time"], row["execution_duration"], row["cpu_request"], row["memory_request_mb"]])
print(f"Merged {45_000} unique rows into {output}")
