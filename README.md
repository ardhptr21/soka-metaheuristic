# SOKA Metaheuristic Scheduling

This project compares Slime Mould Algorithm (SMA) and Aquila Optimizer (AO) on the same CloudSim scheduling model used by `soka-heuristic`.

## Static topology

- 2 datacenters.
- 2 hosts per datacenter, 4 hosts total.
- 2 fixed VMs per host, 8 VMs total.
- VM IDs 0-1 are on Host 0, 2-3 on Host 1, 4-5 on Host 2, and 6-7 on Host 3.

## Workload schedule

The input file is `data/workload.csv`. It must contain at least 45,000 unique valid rows. The loader rejects duplicate task IDs and duplicate complete rows.

The existing trace downloader can be used to build a larger source file. For example, request at least 45,000 records and enough shards for the selected cell, then save the result as `data/workload.csv` before running the simulation.

```bash
python3 scripts/download_google_trace_sample.py --cell a --format parquet --limit 45000 --max-shards 64 --output data/workload.csv
```

The simulation consumes contiguous, non-overlapping chunks:

```text
dry run 1:  1,000 rows × 3
dry run 2:  2,000 rows × 3
...
dry run 5: 5,000 rows × 3
```

This produces 15 workload chunks per algorithm and 30 simulation runs when both algorithms are enabled. The largest individual workload contains 5,000 cloudlets; the full experiment consumes 45,000 unique rows.

## Run

```bash
chmod +x run.sh
./run.sh
./run.sh --algorithm SMA
./run.sh --algorithm AO
./run.sh --algorithm SMA,AO
./run.sh --print-output
```

The optimizer objective uses 60% makespan and 40% execution cost. Population size and iteration count are recorded in `results/configuration.txt`.

## Results

```text
results/runs.csv          Every algorithm/chunk result
results/summary.csv       Per-size and overall aggregates
results/configuration.txt Experiment configuration
results/comparison.png    SMA/AO curves from 1,000 to 5,000 cloudlets
results/overall.png       Overall aggregate comparison
simulation.log            Raw runner log
```
