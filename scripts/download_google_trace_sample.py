#!/usr/bin/env python3
"""Stream a small, reproducible sample from the public Google ClusterData2019 trace.

The full trace is far too large for the lab VM. This script reads public GCS JSON
shards, keeps tasks with SUBMIT -> one SCHEDULE -> FINISH and no interruption,
then applies the project's quartile-to-category transformations.
"""
import argparse
import gzip
import json
import math
import shutil
import tempfile
import urllib.request
from pathlib import Path

SUBMIT, SCHEDULE, EVICT, FAIL, FINISH, KILL = 0, 3, 4, 5, 6, 7

def quantile(values, q):
    values = sorted(values)
    if not values:
        return 0.0
    pos = (len(values) - 1) * q
    lo, hi = math.floor(pos), math.ceil(pos)
    if lo == hi:
        return values[lo]
    return values[lo] + (values[hi] - values[lo]) * (pos - lo)

def category(value, cuts, labels):
    for cut, label in zip(cuts, labels[:-1]):
        if value <= cut:
            return label
    return labels[-1]

def collect(cell, limit, max_shards):
    complete = {}
    for shard in range(max_shards):
        url = f"https://storage.googleapis.com/clusterdata_2019_{cell}/instance_events-{shard:012d}.json.gz"
        print(f"Reading {url}")
        try:
            response = urllib.request.urlopen(url, timeout=60)
        except Exception as exc:
            print(f"Skipping unavailable shard: {exc}")
            continue
        with gzip.GzipFile(fileobj=response) as stream:
            for raw in stream:
                event = json.loads(raw)
                key = (event.get("collection_id"), event.get("instance_index"))
                if None in key:
                    continue
                state = complete.setdefault(key, {
                    "submit": None, "schedule": [], "finish": None,
                    "interrupted": False, "cpus": None, "memory": None,
                })
                kind = int(event.get("type", -1))
                if kind == SUBMIT and state["submit"] is None:
                    state["submit"] = int(event["time"])
                    request = event.get("resource_request") or {}
                    state["cpus"] = float(request.get("cpus", 0) or 0)
                    state["memory"] = float(request.get("memory", 0) or 0)
                elif kind == SCHEDULE:
                    state["schedule"].append(int(event["time"]))
                elif kind in (EVICT, FAIL, KILL):
                    state["interrupted"] = True
                elif kind == FINISH:
                    state["finish"] = int(event["time"])
                if len([x for x in complete.values() if valid(x)]) >= limit:
                    return [x for x in complete.values() if valid(x)][:limit]
    return [x for x in complete.values() if valid(x)][:limit]

def collect_parquet(cell, limit, max_shards, start_shard=0):
    """Read the compact public Parquet shards instead of the much larger JSON shards."""
    import pyarrow.parquet as pq
    records = []
    for shard in range(start_shard, start_shard + max_shards):
        url = f"https://storage.googleapis.com/clusterdata_2019_{cell}/instance_events-{shard:012d}.parquet.gz"
        print(f"Reading {url}")
        with tempfile.TemporaryDirectory() as temp:
            gz_path = Path(temp) / "events.parquet.gz"
            parquet_path = Path(temp) / "events.parquet"
            try:
                with urllib.request.urlopen(url, timeout=60) as source, gz_path.open("wb") as target:
                    shutil.copyfileobj(source, target)
                with gz_path.open("rb") as probe:
                    magic = probe.read(4)
                if magic == b"PAR1":
                    shutil.copyfile(gz_path, parquet_path)
                else:
                    with gzip.open(gz_path, "rb") as source, parquet_path.open("wb") as target:
                        shutil.copyfileobj(source, target)
            except Exception as exc:
                print(f"Skipping unavailable shard: {exc}")
                continue
            parquet = pq.ParquetFile(parquet_path)
            # Keep only one shard's event state in memory. The earlier global
            # map grew with the entire trace and could be killed on a small VM.
            complete = {}
            for batch in parquet.iter_batches(batch_size=100_000):
                for event in batch.to_pylist():
                    key = (event.get("collection_id"), event.get("instance_index"))
                    if None in key:
                        continue
                    state = complete.setdefault(key, {
                        "submit": None, "schedule": [], "finish": None,
                        "interrupted": False, "cpus": None, "memory": None,
                    })
                    kind = int(event.get("type", -1))
                    if kind == SUBMIT and state["submit"] is None:
                        state["submit"] = int(event["time"])
                        request = event.get("resource_request") or {}
                        state["cpus"] = float(request.get("cpus", 0) or 0)
                        state["memory"] = float(request.get("memory", 0) or 0)
                    elif kind == SCHEDULE:
                        state["schedule"].append(int(event["time"]))
                    elif kind in (EVICT, FAIL, KILL):
                        state["interrupted"] = True
                    elif kind == FINISH:
                        state["finish"] = int(event["time"])
                # Checking every event would repeatedly scan the entire state map
                # and becomes quadratic on a large trace shard. Check once per
                # Arrow batch instead.
            shard_records = [x for x in complete.values() if valid(x)]
            records.extend(shard_records)
            print(f"Collected {len(records)} clean tasks after shard {shard}")
            if len(records) >= limit:
                return records[:limit]
            del complete, shard_records
    return records[:limit]

def valid(x):
    return (x["submit"] is not None and len(x["schedule"]) == 1 and
            x["finish"] is not None and not x["interrupted"] and
            x["finish"] > x["schedule"][0] > x["submit"] and
            x["cpus"] > 0 and x["memory"] > 0)

def write_csv(records, output):
    if len(records) < 1000:
        raise RuntimeError(f"Only found {len(records)} clean tasks; increase --max-shards or change --cell")
    first = min(x["submit"] for x in records)
    cpu_cuts = [quantile([x["cpus"] for x in records], q) for q in (0.25, 0.75)]
    mem_cuts = [quantile([x["memory"] for x in records], q) for q in (0.25, 0.50, 0.75)]
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as f:
        f.write("task_id,start_time,execution_duration,cpu_request,memory_request_mb\n")
        for i, x in enumerate(sorted(records, key=lambda v: v["submit"]), 1):
            cpu = category(x["cpus"], cpu_cuts, [1, 2, 4])
            memory = category(x["memory"], mem_cuts, [512, 1024, 2048, 4096])
            start = (x["submit"] - first) / 1_000_000
            duration = (x["finish"] - x["schedule"][0]) / 1_000_000
            f.write(f"{i},{start:.6f},{duration:.6f},{cpu},{memory}\n")
    print(f"Wrote {len(records)} tasks to {output}")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cell", default="a")
    parser.add_argument("--limit", type=int, default=1000)
    parser.add_argument("--max-shards", type=int, default=8)
    parser.add_argument("--start-shard", type=int, default=0)
    parser.add_argument("--format", choices=("parquet", "json"), default="parquet")
    parser.add_argument("--output", type=Path, default=Path("data/workload_google2019.csv"))
    args = parser.parse_args()
    records = (collect_parquet(args.cell, args.limit, args.max_shards, args.start_shard)
               if args.format == "parquet" else collect(args.cell, args.limit, args.max_shards))
    write_csv(records, args.output)

if __name__ == "__main__":
    main()
