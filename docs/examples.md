# RunLedger Usage Examples

## 1. Find the best configuration in a hyperparameter sweep

Scan the sweep folder, then aggregate.

```bash
runledger scan sweep-results/ --batch lr-sweep
runledger aggregate --metric accuracy --agg MAX --group-by config.learning_rate --batch lr-sweep
```

Output: a table showing the best accuracy achieved at each learning rate.

## 2. Compare two experiments side-by-side

After searching, pick two run IDs and diff them.

```bash
runledger search --metric accuracy --op gt --value 0.9 --batch defense --summary
# Note the IDs (e.g., 12 and 15)
runledger diff 12 15
```

The table highlights every metric that changed, with numeric deltas coloured green/red.

## 3. Monitor a sweep for a specific condition

Save a compound search that you run repeatedly.

```bash
runledger search --metric results[].sst2_cacc --op gt --value 0.9 --metric results[].fin_asr --op lt --value 0.5 --combine and --batch defense --save high-cacc-low-asr
# Later, after adding new runs:
runledger search --saved high-cacc-low-asr
```

## 4. Ingest per-step training logs (JSONL)

A training script writes one JSON line per epoch to `training.jsonl`:

```text
{"step":0,"loss":0.95}
{"step":1,"loss":0.82}
{"step":2,"loss":0.67}
```

Ingest and search:

```bash
runledger scan logs/ --batch training --jsonl
runledger search --metric loss --op lt --value 0.7 --batch training
```

## 5. Track changes to an experiment over time

Every time you re-scan a modified file, RunLedger creates a new version. To see what changed:

```bash
runledger diff --history 12 --batch lr-sweep
```

This prints a diff between every consecutive version of run #12.

## 6. Export results for external analysis

```bash
runledger export --metric accuracy --op gt --value 0.9 --batch defense --block 0 --output results.txt
```

## 7. Launch the guided wizard (new users)

```bash
runledger guided
```

Walk through folder selection, scanning, metric filtering, and search—no flags to remember.

## 8. Verbose metrics discovery

```bash
runledger metrics --batch sample --verbose
```

Shows every metric’s shorthand key, full dot-path, and depth.