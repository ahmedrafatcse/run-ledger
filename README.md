# RunLedger

[![PyPI version](https://img.shields.io/pypi/v/runledger-cli)](https://pypi.org/project/runledger-cli/)
[![Docker Pulls](https://img.shields.io/docker/pulls/ahmedrafatdev/runledger)](https://hub.docker.com/r/ahmedrafatdev/runledger)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**A zero‑instrumentation experiment registry.**  
Point RunLedger at a folder of JSON experiment files and search, filter, aggregate, diff, and export the results — no SDK, no code changes, no cloud.

Most experiment trackers make you instrument your training code before they can help you. RunLedger reads the JSON you're already writing (no `wandb.init()`, no `mlflow.log_metric()`) and indexes it in real PostgreSQL, giving you full relational search: structured comparisons, full‑text, fuzzy matching, and exact array‑element lookups, from one CLI command.

Backed by Postgres, served by a Spring Boot API, fronted by a Python CLI.

---

## Install

```bash
pip install runledger-cli
```

Requires Docker. If Docker isn't installed, `runledger` detects that on first run and links you straight to the installer; everything else (Postgres, the backend) is managed for you.

### Troubleshooting: command not found

<details>
<summary>Windows – `runledger` not recognized</summary>

On some Windows systems the Python Scripts folder is not automatically added to PATH. Two options:

1. **Add the Scripts folder to PATH permanently** (recommended):
   - Find the install location: `pip show runledger-cli | findstr Location`
   - Navigate to that folder, then up one level to the `Scripts` directory.
   - Add that `Scripts` path to your system PATH via **System Properties > Environment Variables**.
   - Restart your terminal.

2. **Use the Python module fallback** (works immediately, no PATH changes):
   ```bash
   python -m cli.ledger
   ```
   All subcommands and the guided wizard work exactly the same way.

Once PATH is configured, `runledger` works as shown in the examples below.
</details>

<details>
<summary>Fedora / RHEL – Backend can't reach PostgreSQL (UnknownHostException)</summary>

Fedora's `firewalld` can block Docker's internal bridge networking, causing the app container to fail DNS resolution for `postgres`. Fix:

```bash
sudo firewall-cmd --zone=trusted --add-interface=docker0 --permanent
sudo firewall-cmd --reload
sudo systemctl restart docker
```

Then try `runledger guided` again. If you still see a port conflict (`port is already allocated`), check if another process is using port 5432 (`sudo ss -tulpn | grep 5432`) and either stop it or change the host port mapping in the CLI's compose file.
</details>

<details>
<summary>Installing from source</summary>

```bash
git clone https://github.com/yourusername/run-ledger.git
cd run-ledger
docker compose up -d
pip install -e .
```
</details>

---

## Quick start

```bash
runledger scan ~/experiments --batch my-sweep
runledger search --metric accuracy --op gt --value 0.9 --batch my-sweep
runledger aggregate --metric accuracy --agg AVG --group-by experiment --batch my-sweep
```

New here? Run `runledger` with no arguments — the guided wizard walks you through your first scan and search.

> **Tip:** you can also use `python -m cli.ledger` interchangeably with `runledger` on any platform.

---

## Features

- **Search any field, no schema required** — structured comparisons (`gt`, `lt`, `eq`…), full‑text with stemming, and fuzzy matching for typos, all on arbitrary dot‑paths, including fields nested inside arrays (`results[].sst2_cacc`).
- **Compound AND/OR** across multiple conditions in one query.
- **Exact match pointers** — array searches tell you exactly which element(s) matched (`results[3].sst2_cacc = 0.933`), not just that the run did.
- **Aggregates & group‑by** — `AVG` / `MAX` / `MIN` / `SUM` / `COUNT`, straight from the CLI.
- **Safe re‑scanning** — ingestion is idempotent; a changed file gets a new version, and `diff --history` shows you what changed and when.
- **Run diff, saved searches, JSONL ingestion, block‑depth control** — see [docs/examples.md](docs/examples.md) for each in action.
- **Self‑hosted, local‑first** — your data never leaves your machine.

---

## Metric paths

Address any value with a dot‑path — `accuracy`, `metrics.loss`, `results[].sst2_cacc`. Shorthand (`cacc`) resolves automatically when unambiguous. Run `runledger metrics --batch <batch>` to see everything discovered in a scan, or add `--verbose` for full paths.

---

## Docs

- [API reference](docs/api.md)
- [Usage examples](docs/examples.md)

---

## License

MIT
