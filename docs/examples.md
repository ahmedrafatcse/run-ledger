# Usage Examples

Concrete examples for the RunLedger CLI. Every command assumes the stack is
already running — `runledger` starts it automatically on first use, or you can
start it yourself with the Docker Compose command in the main README.

---

## Scanning a folder

```bash
runledger scan ~/experiments --batch sweep-42
```

Every `.json` file in the folder is ingested as a run and tagged with the
batch name. Files with the same `_source.file` and `_source.index` as an
existing run create a **new version** of that run — never an overwrite. The
old version stays in history.

The scan is idempotent: re-running it against the same folder with no changes
produces no new rows. Changing a file produces a new version.

### Output

```
Successfully ingested 12 run(s) into batch 'sweep-42'
```

---

## What's in a batch

```bash
runledger metrics --batch sweep-42
```

Lists every metric key discovered in the batch:

```
accuracy
loss
learning_rate
sst2_cacc
```

Add `--verbose` to see the full path of each key:

```bash
runledger metrics --batch sweep-42 --verbose
```

```
accuracy       →  metrics.accuracy
loss           →  metrics.loss
learning_rate  →  config.optimizer.settings.learning_rate
sst2_cacc      →  results[].sst2_cacc
```

The paths are what you can query against. A shorthand key (`accuracy`) resolves
automatically when unambiguous within the batch.

### Check the stack status

```bash
runledger status
```

```
Backend is running on http://localhost:8080
Postgres is healthy
```

---

## Basic queries

Comparison operators: `gt`, `gte`, `lt`, `lte`, `eq`.

```bash
runledger search --metric accuracy --op gt --value 0.9 --batch sweep-42
```

### Add `--json` for machine-readable output

```bash
runledger search --metric accuracy --op gt --value 0.9 --batch sweep-42 --json
```

```json
{
  "totalElements": 3,
  "content": [
    {
      "id": 7,
      "payload": { "metrics": { "accuracy": 0.95, ... }, ... }
    }
  ]
}
```

### Text equality

```bash
runledger search --metric status --op eq --value completed --batch sweep-42
```

Numeric and text values are auto-detected. `0.9` is compared numerically;
`completed` is compared as a string.

---

## Querying nested paths

Any dot-path works, at any depth.

```bash
runledger search --metric config.optimizer.settings.learning_rate \
  --op lt --value 0.001 --batch sweep-42
```

Nested text fields work too:

```bash
runledger search --metric config.model.name --op eq --value ResNet18 --batch sweep-42
```

---

## Querying arrays

Use `[]` to reach inside an array of objects. Every element is tested.

```bash
runledger search --metric results[].sst2_cacc --op lt --value 0.7 --batch sweep-42
```

When an array query matches, RunLedger tells you **which element** matched, not
just that the run did:

```
sweep-42/run-7.json
  results[3].sst2_cacc = 0.62
  results[9].sst2_cacc = 0.68
```

`sweep-42/run-11.json
results[1].sst2_cacc = 0.58
```

That's the difference between "some element in this array passed the filter"
and "element 3 and element 9 passed, and here are their values."

Nested arrays work as long as only one level uses `[]`:

```bash
runledger search --metric clients[].sweep[].fin_asr --op lt --value 0.1 --batch sweep-42
```

> **Note:** two levels of `[]` in one path are not currently supported. If you
> need to reach deeper, use the full dot-path or split the query.

---

## Compound conditions

Combine multiple conditions with AND or OR.

```bash
runledger search \
  --metric accuracy --op gt --value 0.9 \
  --metric loss     --op lt --value 0.2 \
  --combine and --batch sweep-42
```

Use `--combine or` to match runs satisfying any condition.

Mixed scalar and array conditions work:

```bash
runledger search \
  --metric accuracy        --op gt --value 0.9 \
  --metric results[].f1    --op gt --value 0.85 \
  --combine and --batch sweep-42
```

---

## Full-text search

Search across all text fields in the payload with stemming, so `weighted`
matches `weight` and `weighting`.

```bash
runledger search --q "weighted averaging" --batch sweep-42
```

### Fuzzy matching

Typos and near-misses are matched with `--fuzzy`. The default threshold is
0.3.

```bash
runledger search --q "avaraging" --fuzzy --batch sweep-42
```

Combines with batch filter:

```bash
runledger search --q "baseline" --fuzzy --batch sweep-42
```

---

## Aggregates

Functions: `AVG`, `MAX`, `MIN`, `SUM`, `COUNT`.

### Simple aggregate

```bash
runledger aggregate --metric accuracy --agg AVG --batch sweep-42
```

```
AVG(accuracy) = 0.8934
```

### Group by a field

```bash
runledger aggregate --metric accuracy --agg AVG --group-by experiment --batch sweep-42
```

```
experiment      AVG(accuracy)
----------      -------------
demo            0.9472
baseline        0.9231
ablation        0.8102
```

### Array-element aggregate

Aggregate over values inside an array — each element contributes to the
aggregate, and the group-by uses a run-level field.

```bash
runledger aggregate --metric results[].accuracy --agg AVG --group-by experiment --batch sweep-42
```

```
experiment      AVG(results[].accuracy)
----------      -----------------------
demo            0.9180
baseline        0.8874
```

### Count all runs in a batch

```bash
runledger aggregate --agg COUNT --batch sweep-42
```

---

## Version history and diff

A run's identity is `(batch, source_file, source_index)`. Resubmitting with
the same identity creates a new version.

### See every version of a run

```bash
runledger diff --history --batch sweep-42 \
  --source-file run-7.json --source-index 0
```

```
v3  2026-04-15 14:23  verified   (current)
v2  2026-04-14 09:11  verified
v1  2026-04-12 18:04  verified
```

### Diff two versions

```bash
runledger diff --batch sweep-42 --source-file run-7.json --source-index 0 \
  --v1 1 --v2 3
```

```
~ metrics.accuracy         0.89  →  0.95
~ config.lr                0.001 →  0.0005
+ metrics.f1               0.91    (added in v3)
```

Lines beginning with `~` are changed, `+` is added, `-` is removed.

---

## Export

Dump a batch or a query result as JSON, for piping into another tool.

```bash
runledger export --batch sweep-42 --json > runs.json
```

Export the results of a query:

```bash
runledger export --metric accuracy --op gt --value 0.9 --batch sweep-42 --json > high-accuracy.json
```

The exported JSON contains the full payload of each run plus its metadata
(id, batch, source file, version, hash, submitted-by, submitted-at).

---

## The same queries in the web UI

Every query above has a UI equivalent. Open `http://localhost:8080` and log in
with a development user ID (see the main README).

| CLI | UI page |
|---|---|
| `runledger search --metric ... --op ... --value ...` | **Compare** (`/query/compare`) |
| `runledger search --q "..." [--fuzzy]` | **Phrase** (`/query/phrase`) |
| `runledger aggregate --metric ... --agg ... --group-by ...` | **Aggregate** (`/query/aggregate`) |
| `runledger metrics --batch <b> --verbose` | **Batches** → click a batch (`/batches/{team}/{batch}`) |
| `runledger diff --history ...` | Run detail → **View history** link |
| `runledger diff --v1 X --v2 Y ...` | History page → compare any two versions |

The UI also adds saved searches (named queries you can re-run) and a submit
form for pasting or uploading a single JSON payload.

---

## A worked example

Scan a folder of experiment results, find the runs that beat a threshold, and
group them by model.

```bash
# 1. Ingest
runledger scan ~/experiments/2026-04-sweep --batch april-sweep

# 2. What did we collect?
runledger metrics --batch april-sweep --verbose

# 3. Which runs beat 0.9 accuracy with low loss?
runledger search \
  --metric accuracy --op gt --value 0.9 \
  --metric loss     --op lt --value 0.2 \
  --combine and --batch april-sweep

# 4. Average accuracy per model
runledger aggregate --metric accuracy --agg AVG --group-by config.model.name --batch april-sweep

# 5. Which runs have a bad array element?
runledger search --metric results[].fin_asr --op gt --value 0.3 --batch april-sweep

# 6. Export the survivors
runledger search --metric accuracy --op gt --value 0.9 --batch april-sweep --json > survivors.json
```
