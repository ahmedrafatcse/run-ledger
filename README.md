# RunLedger

**Zero-instrumentation experiment registry and search engine.**  
Point to a folder of JSON experiment files and instantly search, filter, aggregate, diff, and export your results—no SDK, no code changes.

## Quick Start

```bash
# 1. Start the backend
docker compose up -d

# 2. Ingest your experiment files
runledger scan ~/experiments --batch my-sweep

# 3. Search
runledger search --metric accuracy --op gt --value 0.9 --batch my-sweep

# 4. Aggregate
runledger aggregate --metric accuracy --agg AVG --group-by experiment --batch my-sweep
```

New to the tool? Try the guided wizard:

```bash
runledger guided
```

## Key Features

### 🔍 Search any JSON field, no schema required
- Structured numeric/text comparisons on arbitrary dot-paths (`metrics.accuracy`, `config.optimizer.learning_rate`).
- Full-text phrase search with stemming.
- Fuzzy trigram search for typos.
- Compound AND/OR queries: combine multiple conditions.
- Batch isolation: group runs by folder and filter within a batch.

### 📊 Aggregates & group-by
Answer research questions directly:

```bash
runledger aggregate --metric accuracy --agg AVG --group-by experiment --batch sweep
```

Supported functions: `AVG`, `MAX`, `MIN`, `SUM`, `COUNT`. Group by any scalar field.

### 🎯 Exact match pointers (“JSON line numbers”)
For array searches (`results[].sst2_cacc < 0.8`), the CLI shows exactly which array elements matched:

```text
Matches:
  • results[0].sst2_cacc = 0.924312
  • results[3].sst2_cacc = 0.933486
```

Works for scalar, array, and compound conditions.

### 🔬 Block-depth control
Limit output to the relevant part of a large JSON payload with `--block 0` (tightest match), `--block 1` (parent), etc.

### 📁 JSONL ingestion
Ingest one-JSON-object-per-line logs (common in training scripts) with `--jsonl`.

### 🔎 Run diff
Compare any two runs side-by-side:

```bash
runledger diff 12 15
```

Or see every version of a re-scanned run with `--history`.

### 💾 Saved searches
Save frequent queries and re-run them with a single command:

```bash
runledger search --metric accuracy --op gt --value 0.9 --batch sweep --save best-accuracy
runledger search --saved best-accuracy
```

### 🧭 Interactive & guided modes
- Filter-as-you-type interactive search (`runledger interactive`).
- Step-by-step wizard for first-time users (`runledger guided`).

### 🔒 Self-hosted, local-first
Runs in Docker with a single `docker compose up -d`.  
All data stays on your machine. No internet, no accounts, no vendor lock-in.

## Installation

### From PyPI (CLI only)

```bash
pip install runledger-cli
```

### From source

```bash
git clone https://github.com/yourusername/run-ledger.git
cd run-ledger
docker compose up -d          # starts PostgreSQL + Spring Boot
pip install -e .              # installs the CLI
```

## Quick Tour

### Scan & Discover

```bash
runledger scan experiments/ --batch my-sweep
```

The CLI prints a schema summary showing every metric found, its full dot-path, and depth. Array keys are shown with `[]` notation (e.g., `results[].cacc`).

### Search

```bash
# Single condition
runledger search --metric loss --op lt --value 0.2 --batch my-sweep --summary

# Compound AND
runledger search --metric accuracy --op gt --value 0.9 --metric loss --op lt --value 0.2 --combine and

# Full-text
runledger search --q "weighted averaging"

# Fuzzy
runledger search --q "weighted avaraging" --fuzzy
```

### Aggregate

```bash
runledger aggregate --metric accuracy --agg AVG --group-by config.learning_rate --batch my-sweep
```

### Diff

```bash
runledger diff 12 15
runledger diff --history 12 --batch my-sweep
```

### Export

```bash
runledger export --metric accuracy --op gt --value 0.9 --batch my-sweep --block 0 --output results.txt
```

### Saved searches

```bash
runledger search --metric accuracy --op gt --value 0.9 --batch sweep --save best-accuracy
runledger search --saved best-accuracy
runledger saved list
runledger saved delete best-accuracy
```

## Metrics-Path Convention

RunLedger uses **dot-separated paths** to address any value in a JSON payload.

| Example | Meaning |
| -------- | ------- |
| `accuracy` | Top-level field |
| `metrics.loss` | Nested field |
| `results[].sst2_cacc` | Field inside each element of an array |
| `phases[].metrics.loss` | Nested field inside array elements |

**Shorthand keys:** After scanning a batch, the CLI shows a shorthand key for every leaf field. Typing `cacc` will automatically resolve to `client.results[].cacc` if that’s the only (or shallowest) occurrence. Use the full path when there’s ambiguity.

Discover available metrics with:

```bash
runledger metrics --batch my-sweep
runledger metrics --batch my-sweep --verbose   # shows full paths
```

## API

See [`docs/api.md`](docs/api.md) for the complete REST API reference.

## Usage Examples

See [`docs/examples.md`](docs/examples.md) for real-world workflows.

## License

MIT