
# RunLedger

[![PyPI version](https://img.shields.io/pypi/v/runledger-cli)](https://pypi.org/project/runledger-cli/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**A self-hosted, SQL-style query engine over your team's experiment JSON.**

Point it at the JSON your runs produce. Query nested fields, aggregate across runs, and pull back the exact array element that matched. Every submission is a version — never an overwrite — so you can see who submitted it, when, and whether the file has changed since.

> *How do you query JSON with no schema?*
> 
> *Who on your team ran this? Has it changed since?*

*Built for labs, thesis groups, and small research teams.*

<!-- Add a 15-second GIF here: search nested JSON → inspect matching array element → aggregate → compare versions. -->

---

## Query your JSON like a database

Experiment output nests metrics inside configs, arrays, and per-sample results. RunLedger provides a purpose-built query interface over those fields — structured comparisons, full-text with stemming, fuzzy matching, compound conditions, aggregation — without a predefined schema or prior metric registration.

```bash
# Comparison operators over any dot-path
runledger search --metric accuracy --op gt --value 0.9 --batch sweep-42

# Reach inside an array — and get back the exact matching element
runledger search --metric results[].sst2_cacc --op lt --value 0.7 --batch sweep-42

# Compound AND/OR across nested paths
runledger search \
  --metric accuracy --op gt --value 0.9 \
  --metric loss     --op lt --value 0.2 \
  --combine and --batch sweep-42

# Aggregate and group, including over arrays
runledger aggregate --metric accuracy          --agg AVG --group-by experiment --batch sweep-42
runledger aggregate --metric results[].accuracy --agg AVG --group-by experiment --batch sweep-42
```

**Exact array-element pointers.** When an array query matches, RunLedger returns *which field matched and where* — the specific element, not just "this run matched":

```
sweep-42/run-7.json
  results[3].sst2_cacc = 0.62
  results[9].sst2_cacc = 0.68
```

That's the difference between "some array element passed your filter" and "element 3 and element 9 passed, and here are their values."

The same query capabilities are available through the web UI — rendered as tables, with the same inline pointers.

---

## What the shared record gives you

**Version history.** Resubmitting the same `_source.file` and `_source.index` creates a new version — never an overwrite. The old version stays. The run detail page links to `/runs/{id}/history` (every version of a run identity) and `/runs/compare?id1=X&id2=Y` (field-by-field diff between two versions).

**Team isolation.** Only your team sees your team's runs. A foreign team's rows are invisible, not forbidden — `SELECT` returns nothing, `/runs/{id}` returns 404. This is enforced at the database layer, so it holds even if a service method forgets to filter.

**Change detection.** Every submission stores a fingerprint of its payload. Open a run and RunLedger recomputes and compares — if the payload was modified after upload, the badge goes red. States: **Verified** / **Tampered** / **Stale** / **Cannot verify**.

---

## Stack

**PostgreSQL 16** with JSONB and GIN indexes. **Spring Boot 3.4** for the REST API and the server-rendered Thymeleaf UI. **Python 3.10+** for the CLI, which manages the Docker stack for you.

Team isolation is enforced by Row-Level Security in the database. The application connects as a non-owner role; every request runs under a session identity Postgres validates. Access rules live in RLS policies, not in application code.

---

## Install

```bash
pip install runledger-cli
runledger guided
```

**You need Docker** — the only required dependency beyond Python. The backend (Postgres + the Java app) runs entirely inside containers. The CLI starts and stops them automatically. If Docker isn't installed, the CLI detects that on first run and links you to the installer.

---

## Quick start

```bash
runledger scan ~/experiments --batch my-sweep
runledger search --metric accuracy --op gt --value 0.9 --batch my-sweep
runledger aggregate --metric accuracy --agg AVG --group-by experiment --batch my-sweep
```

New here? Run `runledger` with no arguments — the guided wizard walks you through your first scan and search.

> **Tip:** `python -m cli.ledger` works interchangeably with `runledger` on any platform.

### Web UI

With the stack running, open `http://localhost:8080` and log in with a development user ID (see [Development identity](#development-identity)).

| Page | What it does |
|---|---|
| **Runs** (`/`) | Everything you can see. Click a file for its payload, integrity badge, and version history. |
| **Compare** (`/query/compare`) | N conditions with AND/OR. Match pointers show which array element hit. |
| **Phrase** (`/query/phrase`) | Full-text search with stemming; optional fuzzy matching for typos. |
| **Aggregate** (`/query/aggregate`) | `AVG` / `MAX` / `MIN` / `SUM` / `COUNT`, with optional group-by. Supervisors get a team selector. |
| **Batches** (`/batches`) | Every batch you can see, grouped by team. Click through to browse its metric keys. |
| **Saved** (`/saved`) | Named queries. Re-run opens the query page with params prefilled. Personal — no one else can see or delete yours. |
| **Submit** (`/submit`) | Paste JSON or upload a file. Researchers only. Requires `_source.file` in the payload. |

Server-rendered throughout. No SPA, no build step.

---

## Installing from source

```bash
git clone https://github.com/ahmedrafatcse/run-ledger.git
cd run-ledger
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
pip install -e .
```

The dev override is required. It publishes port `8080`, which is what the browser UI and the Python tests expect. Starting with only `docker-compose.yml` doesn't expose `8080` and the UI will not be reachable.

### Troubleshooting

<details>
<summary>Windows – <code>runledger</code> not recognized</summary>

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
</details>

<details>
<summary>Fedora / RHEL – Backend can't reach PostgreSQL (UnknownHostException)</summary>

Fedora's `firewalld` can block Docker's internal bridge networking, causing the app container to fail DNS resolution for `postgres`. Fix:

```bash
sudo firewall-cmd --zone=trusted --add-interface=docker0 --permanent
sudo firewall-cmd --reload
sudo systemctl restart docker
```

If you still see a port conflict (`port is already allocated`), check whether another process is using 5432 (`sudo ss -tulpn | grep 5432`) and either stop it or change the host port mapping.
</details>

---

## Metric paths

Address any value with a dot-path — `accuracy`, `metrics.loss`, `results[].sst2_cacc`. Shorthand (`cacc`) resolves automatically when unambiguous. Run `runledger metrics --batch <batch>` to see everything discovered in a scan, or add `--verbose` for full paths.

---

## Development identity

The browser login is a **development identity stub, not authentication**. There is no password, no OAuth, no SSO. You enter a user ID; the session is populated from `app_users`.

**Do not expose this configuration to untrusted users or networks.** A network boundary does not turn a stub into authentication — every request that reaches the app carries whatever user ID the caller declares. A real deployment needs an authentication mechanism in front of this before relying on the identity model for access control.

Demo users from `demo/seed.sql`:

| ID | Role | Team |
|---|---|---|
| `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` | researcher | Team A |
| `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb` | researcher | Team B |
| `cccccccc-cccc-cccc-cccc-cccccccccccc` | supervisor | assigned to both teams |

Visit `http://localhost:8080/login` and paste an ID. For API access, pass the same ID in an `X-User-Id` header. Role and team are looked up server-side — the client cannot declare which team it belongs to, but it *can* declare which user it is, which is exactly why this stub isn't authentication.

---

## What this doesn't claim yet

- **Not tamper-evident.** The change-detection check catches a payload modified while its stored fingerprint was left unchanged. It does **not** detect a privileged database actor who rewrites both consistently. Closing that gap requires a hash chain, an independent verifier, and an external anchor. Not built.
- **Not authenticated.** The browser login is a development stub; the API trusts an `X-User-Id` header. Neither substitutes for real authentication in a deployed system. See [Development identity](#development-identity).
- **Not RLS on everything.** Row-Level Security is enabled for `run`, `batch_schema`, and `saved_search`. The identity and team tables (`teams`, `app_users`, `supervisor_team_assignments`) are not RLS-protected — they hold shared configuration and identity data, and access to them is controlled at the application layer.
- **Not a SQL console.** The query interface accepts RunLedger commands (`--metric`, `--op`, `--value`, `--combine`), not arbitrary SQL statements. PostgreSQL is the engine; the query surface is purpose-built.
- **Not multi-tenant.** One deployment serves one organization. There is no cross-organization tenancy, no per-org isolation above the team level, and no managed cloud offering.

Naming these limits is more useful than rounding up. Each constraint is documented in code and covered by tests, so the scope is visible and changeable.

---

## Docs

- [API reference](https://github.com/ahmedrafatcse/run-ledger/blob/master/docs/api.md)
- [Usage examples](https://github.com/ahmedrafatcse/run-ledger/blob/master/docs/examples.md)

---

## License

MIT
