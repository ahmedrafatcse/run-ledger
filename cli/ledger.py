#!/usr/bin/env python3
"""
RunLedger CLI – zero‑friction experiment search.

Usage:
    python ledger.py scan /path/to/folder [--batch NAME] [--jsonl]
    python ledger.py search --metric <key> --op <gt|lt|eq|...> --value <v> [--batch NAME] [--summary] [--save <name>]
    python ledger.py search --metric <key1> --op <op1> --value <v1> --metric <key2> --op <op2> --value <v2> [--combine and|or]
    python ledger.py search --q <phrase> [--fuzzy] [--batch NAME] [--summary]
    python ledger.py search --saved <name>
    python ledger.py export ... (same filters as search)
    python ledger.py preview --metric <key> --op <gt|lt|eq|...> --value <v> [--batch NAME]
    python ledger.py metrics [--batch NAME] [--verbose]
    python ledger.py interactive [--batch NAME]
    python ledger.py diff <id1> <id2> [--history] [--batch BATCH] [--json]
    python ledger.py aggregate --metric <key> --agg <AVG|MAX|MIN|SUM|COUNT> [--group-by <field>] [--batch NAME] [--json]
    python ledger.py saved list [--batch]
    python ledger.py saved delete <name> [--batch]
    python ledger.py stop
    python ledger.py status
"""

import argparse
import json
import os
import sys
import subprocess
import time
from pathlib import Path
from typing import Any, Dict

import requests
from rich.console import Console
from rich.panel import Panel
from rich.progress import Progress
from rich.prompt import Prompt, Confirm
from rich.table import Table

# ------------------------------------------------------------
# Force UTF‑8 output so Rich can render box‑drawing characters
# ------------------------------------------------------------
try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

# ------------------------------------------------------------
# Configuration
# ------------------------------------------------------------
_BASE = os.environ.get("RUNLEDGER_BASE_URL", "http://localhost:8080")
API_BASE = f"{_BASE}/api/runs"
HEALTH_URL = f"{_BASE}/actuator/health"
COMPOSE_FILE = os.path.join(os.path.dirname(__file__), "docker-compose.yml")
PAGE_SIZE = 10

console = Console()

# ------------------------------------------------------------
# Helpers
# ------------------------------------------------------------
def check_docker():
    result = subprocess.run("docker version", shell=True,
                            capture_output=True, text=True)
    if result.returncode != 0:
        console.print("[bold red]Docker is not installed or not reachable.[/bold red]")
        console.print("Please install Docker Desktop from [link]https://www.docker.com/get-started[/link]")
        return False
    return True

def backend_running():
    try:
        resp = requests.get(HEALTH_URL, timeout=2)
        return resp.status_code == 200
    except requests.RequestException:
        return False

def wait_for_backend(timeout=60):
    console.print("Waiting for the RunLedger backend to be ready...", end="")
    start = time.time()
    while time.time() - start < timeout:
        if backend_running():
            console.print("[green] ready![/green]")
            return True
        time.sleep(2)
        console.print(".", end="")
    console.print("\n[red]Timed out waiting for backend.[/red]")
    return False

def start_backend():
    compose_file = COMPOSE_FILE
    if not os.path.exists(compose_file):
        console.print("[red]Cannot find docker-compose.yml[/red]")
        sys.exit(1)
    console.print("Starting RunLedger backend...")
    subprocess.run(
        ["docker", "compose", "-f", compose_file, "-p", "runledger", "up", "-d"],
        shell=True, check=True
    )
    if wait_for_backend():
        console.print("[green]RunLedger backend is running.[/green]")
    else:
        console.print("[red]Failed to start RunLedger. Check Docker logs.[/red]")
        sys.exit(1)

def stop_backend():
    compose_file = COMPOSE_FILE
    if os.path.exists(compose_file):
        subprocess.run(
            ["docker", "compose", "-f", compose_file, "-p", "runledger", "down"],
            shell=True, check=True
        )
        console.print("RunLedger backend stopped.")
    else:
        console.print("[red]Cannot find docker-compose.yml[/red]")

def ensure_backend():
    """Start the backend if it's not already running."""
    if not backend_running():
        start_backend()

# ------------------------------------------------------------
# Ingestion
# ------------------------------------------------------------
def ingest_folder(folder: str, batch: str = None, jsonl: bool = False):
    path = Path(folder).expanduser()
    if not path.exists():
        console.print(f"[red]Folder not found: {folder}[/red]")
        return

    if batch is None:
        batch = path.name

    json_files = list(path.glob("*.json")) if not jsonl else list(path.glob("*.jsonl")) + list(path.glob("*.json"))
    if not json_files:
        console.print(f"[yellow]No .json files found in {folder}[/yellow]")
        return

    if jsonl:
        console.print(f"Found {len(json_files)} JSONL file(s) in '{batch}'. Ingesting line by line...")
    else:
        console.print(f"Found {len(json_files)} JSON file(s) in '{batch}'. Ingesting...")

    count = 0
    with Progress() as progress:
        task = progress.add_task("[cyan]Ingesting...", total=len(json_files))
        for jfile in json_files:
            progress.update(task, description=f"Ingesting {jfile.name}...")
            try:
                with open(jfile, "r", encoding="utf-8") as fh:
                    if jsonl:
                        for idx, line in enumerate(fh):
                            line = line.strip()
                            if not line:
                                continue
                            try:
                                run = json.loads(line)
                            except json.JSONDecodeError as e:
                                console.print(f"[red]Skipping {jfile.name} line {idx+1}: {e}[/red]")
                                continue
                            payload = {
                                "payload": run,
                                "batch": batch,
                            }
                            payload["payload"]["_source"] = {
                                "file": jfile.name,
                                "index": idx,
                            }
                            try:
                                resp = requests.post(API_BASE, json=payload)
                                if resp.status_code == 201:
                                    count += 1
                                else:
                                    console.print(f"[red]Failed to ingest line {idx+1} from {jfile.name}: {resp.status_code}[/red]")
                            except requests.RequestException as e:
                                console.print(f"[red]Error sending {jfile.name}: {e}[/red]")
                                break
                    else:
                        data = json.load(fh)
                        if isinstance(data, dict):
                            data = [data]
                        for idx, run in enumerate(data):
                            payload = {
                                "payload": run,
                                "batch": batch,
                            }
                            payload["payload"]["_source"] = {
                                "file": jfile.name,
                                "index": idx,
                            }
                            try:
                                resp = requests.post(API_BASE, json=payload)
                                if resp.status_code == 201:
                                    count += 1
                                else:
                                    console.print(f"[red]Failed to ingest run {idx} from {jfile.name}: {resp.status_code}[/red]")
                            except requests.RequestException as e:
                                console.print(f"[red]Error sending {jfile.name}: {e}[/red]")
                                break
            except Exception as e:
                console.print(f"[red]Skipping {jfile.name}: {e}[/red]")
                continue
            progress.advance(task)

    console.print(f"[green]Successfully ingested {count} run(s) into batch '{batch}'.[/green]")
    if jsonl:
        console.print("[dim]Tip: JSONL ingestion creates one run per line. Use --jsonl flag to process line‑delimited files.[/dim]")

# ------------------------------------------------------------
# API wrappers
# ------------------------------------------------------------
def fetch_metrics(batch: str = None) -> list:
    params = {"batch": batch} if batch else {}
    try:
        resp = requests.get(f"{API_BASE}/metrics", params=params)
        if resp.status_code == 200:
            return resp.json()
        else:
            console.print(f"[red]Failed to fetch metrics (HTTP {resp.status_code}).[/red]")
            return []
    except requests.RequestException as e:
        console.print(f"[red]Error fetching metrics: {e}[/red]")
        return []

def fetch_verbose_metrics(batch: str) -> list:
    params = {"batch": batch, "verbose": "true"}
    try:
        resp = requests.get(f"{API_BASE}/metrics", params=params)
        if resp.status_code == 200:
            return resp.json()
        else:
            console.print(f"[red]Failed to fetch verbose metrics (HTTP {resp.status_code}).[/red]")
            return []
    except requests.RequestException as e:
        console.print(f"[red]Error fetching verbose metrics: {e}[/red]")
        return []

def perform_search(metric=None, op=None, value=None, q=None, fuzzy=False,
                   batch=None, page=0, size=PAGE_SIZE, pointers=True):
    """Single‑condition GET search. Never sends block – CLI handles truncation."""
    params = {
        "page": page,
        "size": size,
        "sort": "created_at,desc",
    }
    if batch:
        params["batch"] = batch
    if q:
        params["q"] = q
        if fuzzy:
            params["fuzzy"] = "true"
    elif metric and op and value is not None:
        params["metric"] = metric
        params["op"] = op
        params["value"] = value
        if pointers:
            params["pointers"] = "true"
    else:
        raise ValueError("Either --q or (--metric, --op, --value) must be provided")

    try:
        resp = requests.get(API_BASE, params=params)
        if resp.status_code == 200:
            return resp.json()
        else:
            console.print(f"[red]Search failed (HTTP {resp.status_code}).[/red]")
            return None
    except requests.RequestException as e:
        console.print(f"[red]Error searching: {e}[/red]")
        return None

def perform_multi_search(filters, combine, batch=None, page=0, size=PAGE_SIZE):
    """Multi‑condition POST search. No block parameter."""
    body = {
        "filters": filters,
        "combine": combine,
    }
    if batch:
        body["batch"] = batch

    try:
        resp = requests.post(f"{API_BASE}/search", json=body)
        if resp.status_code == 200:
            return resp.json()
        else:
            console.print(f"[red]Multi‑search failed (HTTP {resp.status_code}).[/red]")
            return None
    except requests.RequestException as e:
        console.print(f"[red]Error in multi‑search: {e}[/red]")
        return None

def save_search(name, batch, params):
    body = {"name": name, "batch": batch, "paramsJson": json.dumps(params)}
    resp = requests.post("http://localhost:8080/api/saved", json=body)
    if resp.status_code != 200:
        console.print(f"[red]Failed to save search: {resp.text}[/red]")
        return False
    return True

def get_saved_search(name, batch):
    params = {"batch": batch} if batch else {}
    resp = requests.get(f"http://localhost:8080/api/saved/{name}", params=params)
    if resp.status_code == 200:
        return resp.json()
    return None

def list_saved_searches(batch):
    params = {"batch": batch} if batch else {}
    resp = requests.get("http://localhost:8080/api/saved", params=params)
    if resp.status_code == 200:
        return resp.json()
    return []

def delete_saved_search(name, batch):
    params = {"batch": batch} if batch else {}
    resp = requests.delete(f"http://localhost:8080/api/saved/{name}", params=params)
    return resp.status_code == 204

# ------------------------------------------------------------
# Block display helpers
# ------------------------------------------------------------
def navigate_json_pointer(doc, pointer: str):
    parts = pointer.lstrip("/").split("/")
    cur = doc
    for p in parts:
        if p == "":
            continue
        if isinstance(cur, dict):
            cur = cur.get(p, None)
            if cur is None:
                return None
        else:
            return None
    return cur

def show_blocks(run: dict, dot_path: str = None, block_levels: list = None):
    payload = run.get("payload", {})
    # Use experiment name, then source file, then run id
    if isinstance(payload, dict):
        source_file = payload.get("_source", {}).get("file") or payload.get("experiment") or f"run-{run['id']}"
    else:
        source_file = f"run-{run['id']}"

    # Show matched pointers if available
    matched = run.get("matched")
    if matched:
        console.print("  Matches:")
        for m in matched:
            val = m.get("value")
            if isinstance(val, float):
                val_str = f"{val:.6f}"
            else:
                val_str = str(val)
            console.print(f"    • {m['pointer']} = {val_str}")
        console.print()

    if dot_path is None:
        # If we have matched pointers, derive a path from the first concrete pointer
        if matched:
            concrete = matched[0]['pointer']
            parts = concrete.replace('[', '.').replace(']', '').split('.')
            dot_path = '.'.join(parts)
        else:
            label = "-- full run (full‑text/fuzzy match)"
            try:
                pretty = json.dumps(payload, indent=2)
            except Exception:
                pretty = str(payload)
            console.print(Panel(pretty, title=f"Run {run['id']} · {source_file}"))
            return

    # Strip "[]" so JSON Pointer navigation works
    parts = [seg.replace("[]", "") for seg in dot_path.split(".")]
    max_block = len(parts) - 1

    if block_levels is None:
        levels = list(range(max_block + 1))
    else:
        levels = [l for l in block_levels if 0 <= l <= max_block]
        if not levels:
            console.print(f"[yellow]Requested block depth(s) out of range. Max depth is {max_block}.[/yellow]")
            return

    for block in sorted(levels, reverse=True):
        if block == 0 and matched:
            # For block=0, show each matched snippet individually
            for i, m in enumerate(matched):
                snippet = m.get("snippet")
                try:
                    pretty = json.dumps(snippet, indent=2)
                except Exception:
                    pretty = str(snippet)
                title = f"Run {run['id']} · {source_file}  |  metric: {dot_path}  |  depth: 0 (match {i+1}/{len(matched)})"
                console.print(Panel(pretty, title=title))
        else:
            if block > 0 and matched:
                # Use the first match's concrete pointer for precise navigation
                # Example: "clients[1].sweep[0].fin_asr" → "/clients/1/sweep"
                concrete = matched[0]['pointer']
                segments = concrete.replace('[', '.').replace(']', '').split('.')
                ancestor_segments = segments[:len(segments) - (block + 1)]
                pointer = "/" + "/".join(ancestor_segments) if ancestor_segments else ""
                node = navigate_json_pointer(payload, pointer) if pointer else payload
            else:
                ancestor_parts = parts[:len(parts) - (block + 1)]
                pointer = "/" + "/".join(ancestor_parts) if ancestor_parts else ""
                node = navigate_json_pointer(payload, pointer) if pointer else payload

            depth_label = ""
            if block == max_block:
                depth_label = " (full run)"
            elif block == 0:
                depth_label = " (tightest match)"
            try:
                pretty = json.dumps(node, indent=2) if node is not None else "null"
            except Exception:
                pretty = str(node)
            console.print(Panel(pretty, title=f"Run {run['id']} · {source_file}  |  metric: {dot_path}  |  depth: {block}{depth_label}"))
# ------------------------------------------------------------
# Export helpers
# ------------------------------------------------------------
def build_export_blocks(content, dot_path, block_levels, include_pointers=True):
    blocks = []
    for run in content:
        payload = run.get("payload", {})
        if isinstance(payload, dict):
            source_file = payload.get("_source", {}).get("file") or payload.get("experiment") or f"run-{run['id']}"
        else:
            source_file = f"run-{run['id']}"

        matched = run.get("matched") if include_pointers else None

        if dot_path is None:
            blocks.append({
                "id": run["id"],
                "sourceFile": source_file,
                "metric": "(full‑text/fuzzy match)",
                "depth": "full",
                "content": payload,
                "matches": matched
            })
            continue

        parts = [seg.replace("[]", "") for seg in dot_path.split(".")]
        max_block = len(parts) - 1

        levels = block_levels if block_levels is not None else list(range(max_block + 1))
        levels = [l for l in levels if 0 <= l <= max_block]
        if not levels:
            levels = [max_block]

        for block in sorted(levels, reverse=True):
            if block == 0 and matched:
                for i, m in enumerate(matched):
                    snippet = m.get("snippet")
                    blocks.append({
                        "id": run["id"],
                        "sourceFile": source_file,
                        "metric": dot_path,
                        "depth": 0,
                        "matchIndex": i + 1,
                        "totalMatches": len(matched),
                        "content": snippet,
                        "pointer": m.get("pointer"),
                        "value": m.get("value")
                    })
            else:
                if block > 0 and matched:
                    concrete = matched[0]['pointer']
                    segments = concrete.replace('[', '.').replace(']', '').split('.')
                    ancestor_segments = segments[:len(segments) - (block + 1)]
                    pointer = "/" + "/".join(ancestor_segments) if ancestor_segments else ""
                    node = navigate_json_pointer(payload, pointer) if pointer else payload
                else:
                    ancestor_parts = parts[:len(parts) - (block + 1)]
                    pointer = "/" + "/".join(ancestor_parts) if ancestor_parts else ""
                    node = navigate_json_pointer(payload, pointer) if pointer else payload
                blocks.append({
                    "id": run["id"],
                    "sourceFile": source_file,
                    "metric": dot_path,
                    "depth": block,
                    "content": node,
                    "matches": matched if block != 0 else None
                })
    return blocks

def format_blocks_as_text(blocks):
    lines = []
    for i, block in enumerate(blocks):
        if i > 0:
            lines.append("")
        lines.append("-" * 70)
        lines.append(f"id: {block['id']}")
        lines.append(f"file: {block['sourceFile']}")
        if block.get("metric"):
            lines.append(f"metric: {block['metric']}")
        depth = block.get("depth")
        if depth is not None:
            depth_str = str(depth)
            if block.get("matchIndex"):
                depth_str += f" (match {block['matchIndex']}/{block['totalMatches']})"
            lines.append(f"depth: {depth_str}")
        pointer = block.get("pointer")
        if pointer:
            val = block.get("value")
            if isinstance(val, float):
                val_str = f"{val:.6f}"
            else:
                val_str = str(val)
            lines.append(f"match: {pointer} = {val_str}")
        matches = block.get("matches")
        if matches:
            lines.append("matches:")
            for m in matches:
                val = m.get("value")
                if isinstance(val, float):
                    val_str = f"{val:.6f}"
                else:
                    val_str = str(val)
                lines.append(f"  • {m['pointer']} = {val_str}")
        lines.append("-" * 70)
        lines.append(json.dumps(block["content"], indent=2))
    return "\n".join(lines) + "\n"

def suggest_array_path(batch, shorthand):
    try:
        resp = requests.get(f"{API_BASE}/metrics", params={"batch": batch, "verbose": "true"})
        if resp.status_code != 200:
            return None
        entries = resp.json()
        scalar_paths = [e["path"] for e in entries if e["key"] == shorthand and "[]" not in e["path"]]
        if scalar_paths:
            return None
        array_paths = [e["path"] for e in entries if e["key"] == shorthand and "[]" in e["path"]]
        if array_paths:
            best = min(array_paths, key=len)
            return f"Hint: '{shorthand}' exists inside arrays. Did you mean: {best}?"
    except Exception:
        pass
    return None

# ------------------------------------------------------------
# Flatten and diff helpers
# ------------------------------------------------------------
def flatten_payload(payload: Any, prefix: str = '') -> Dict[str, Any]:
    result = {}
    if isinstance(payload, dict):
        for k, v in payload.items():
            if k == '_source':
                continue
            path = f"{prefix}.{k}" if prefix else k
            if isinstance(v, (dict, list)):
                result.update(flatten_payload(v, path))
            else:
                result[path] = v
    elif isinstance(payload, list):
        for i, item in enumerate(payload):
            path = f"{prefix}[{i}]"
            if isinstance(item, (dict, list)):
                result.update(flatten_payload(item, path))
            else:
                result[path] = item
    else:
        result[prefix] = payload
    return result

def compute_delta(val1, val2):
    try:
        v1 = float(val1)
        v2 = float(val2)
        delta = v2 - v1
        if delta > 0:
            return f"+{delta:.6f}", "green"
        elif delta < 0:
            return f"{delta:.6f}", "red"
        else:
            return "0", "white"
    except (ValueError, TypeError):
        if val1 != val2:
            return "changed", "yellow"
        return "unchanged", "white"

# ------------------------------------------------------------
# Non‑interactive CLI outputs
# ------------------------------------------------------------
def build_shorthand_map(batch):
    """Return dict {shorthand_key: [full_path, ...]} from verbose metrics."""
    entries = fetch_verbose_metrics(batch)
    mapping = {}
    for e in entries:
        mapping.setdefault(e['key'], []).append(e['path'])
    return mapping

def cli_metrics(args):
    if args.verbose:
        entries = fetch_verbose_metrics(args.batch)
        if not entries:
            console.print("[yellow]No metrics found.[/yellow]")
            return
        table = Table(title="Verbose Metrics")
        table.add_column("Shorthand Key", style="cyan")
        table.add_column("Full Dot‑Path", style="green")
        table.add_column("Depth", justify="right")
        for item in entries:
            table.add_row(item["key"], item["path"], str(item["depth"]))
        console.print(table)
    else:
        keys = fetch_metrics(args.batch)
        print(json.dumps(keys))

def cli_search(args):
    if args.saved:
        saved = get_saved_search(args.saved, args.batch)
        if not saved:
            console.print(f"[red]Saved search '{args.saved}' not found.[/red]")
            sys.exit(1)
        params = json.loads(saved["paramsJson"])
        args.metrics = params.get("metrics", [])
        args.ops = params.get("ops", [])
        args.values = params.get("values", [])
        args.combine = params.get("combine", "and")
        args.batch = params.get("batch", args.batch)

    filters = None
    if args.metrics and args.ops and args.values:
        if len(args.metrics) != len(args.ops) or len(args.metrics) != len(args.values):
            console.print("[red]Number of --metric, --op, --value arguments must match.[/red]")
            sys.exit(1)
        filters = [
            {"metric": m, "op": op, "value": v}
            for m, op, v in zip(args.metrics, args.ops, args.values)
        ]

    if args.save:
        if not filters:
            console.print("[red]No search parameters to save.[/red]")
            sys.exit(1)
        save_params = {
            "metrics": [f["metric"] for f in filters],
            "ops": [f["op"] for f in filters],
            "values": [f["value"] for f in filters],
            "combine": args.combine or "and",
            "batch": args.batch
        }
        if save_search(args.save, args.batch, save_params):
            console.print(f"[green]Saved search '{args.save}'.[/green]")
        return

    pointers_enabled = getattr(args, "pointers", True)
    combine = args.combine if args.combine else "and"

    if args.q:
        data = perform_search(q=args.q, fuzzy=args.fuzzy, batch=args.batch, page=0)
    elif filters and len(filters) > 1:
        data = perform_multi_search(filters, combine, batch=args.batch, page=0)
    elif filters:
        f = filters[0]
        data = perform_search(metric=f["metric"], op=f["op"], value=f["value"],
                              batch=args.batch, page=0, pointers=pointers_enabled)
    else:
        console.print("[red]No search parameters provided.[/red]")
        sys.exit(1)

    if data is None:
        sys.exit(1)

    if args.summary:
        content = data.get("content", [])
        if not content:
            print("No matching runs found.")
            if args.batch and filters and len(filters) == 1:
                metric = filters[0]["metric"]
                if "." not in metric and "[]" not in metric:
                    hint = suggest_array_path(args.batch, metric)
                    if hint:
                        print(hint)
            return
        table = Table(title="Search Results (summary)")
        table.add_column("ID", justify="right", style="cyan")
        table.add_column("Source File", justify="left", style="green")
        for run in content:
            run_id = run["id"]
            payload = run.get("payload", {})
            source_file = payload.get("experiment") or payload.get("_source", {}).get("file") or f"run-{run_id}"
            table.add_row(str(run_id), source_file)
        console.print(table)
        return

    if args.json:
        print(json.dumps(data, indent=2))
        return

    content = data.get("content", [])
    if not content:
        print("No matching runs found.")
        if args.batch and filters and len(filters) == 1:
            metric = filters[0]["metric"]
            if "." not in metric and "[]" not in metric:
                hint = suggest_array_path(args.batch, metric)
                if hint:
                    print(hint)
        return

    dot_path = None
    if not args.q and filters and len(filters) == 1:
        metric = filters[0]["metric"]
        if "." in metric or "[]" in metric:
            dot_path = metric
        else:
            first_payload = content[0].get("payload", {})
            dot_path = find_shallowest_path(first_payload, metric)

    block_levels = None
    if args.block is not None:
        block_levels = [args.block]

    for run in content:
        show_blocks(run, dot_path, block_levels)

def cli_export(args):
    filters = None
    if args.metrics and args.ops and args.values:
        if len(args.metrics) != len(args.ops) or len(args.metrics) != len(args.values):
            console.print("[red]Number of --metric, --op, --value arguments must match.[/red]")
            sys.exit(1)
        filters = [
            {"metric": m, "op": op, "value": v}
            for m, op, v in zip(args.metrics, args.ops, args.values)
        ]

    pointers_enabled = getattr(args, "pointers", True)
    combine = args.combine if args.combine else "and"

    if args.q:
        data = perform_search(q=args.q, fuzzy=args.fuzzy, batch=args.batch, page=0)
    elif filters and len(filters) > 1:
        data = perform_multi_search(filters, combine, batch=args.batch, page=0)
    elif filters:
        f = filters[0]
        data = perform_search(metric=f["metric"], op=f["op"], value=f["value"],
                              batch=args.batch, page=0, pointers=pointers_enabled)
    else:
        console.print("[red]No search parameters provided.[/red]")
        sys.exit(1)

    if data is None:
        sys.exit(1)

    content = data.get("content", [])
    if not content:
        if args.output:
            Path(args.output).write_text("", encoding="utf-8")
        else:
            print("")
        return

    dot_path = None
    if not args.q and filters and len(filters) == 1:
        metric = filters[0]["metric"]
        if "." in metric or "[]" in metric:
            dot_path = metric
        else:
            first_payload = content[0].get("payload", {})
            dot_path = find_shallowest_path(first_payload, metric)

    block_levels = None
    if args.block is not None:
        block_levels = [args.block]

    blocks = build_export_blocks(content, dot_path, block_levels, include_pointers=pointers_enabled)

    if args.format == "json":
        out = json.dumps(blocks, indent=2)
    else:
        out = format_blocks_as_text(blocks)

    if args.output:
        Path(args.output).write_text(out, encoding="utf-8")
        console.print(f"[green]Exported {len(blocks)} block(s) to {args.output}[/green]")
    else:
        print(out)

def cli_preview(args):
    pointers_enabled = getattr(args, "pointers", True)
    data = perform_search(metric=args.metric, op=args.op, value=args.value,
                          batch=args.batch, page=0, size=1,
                          pointers=pointers_enabled)
    if data is None or not data.get("content"):
        console.print("[red]No matching run found.[/red]")
        sys.exit(1)

    run = data["content"][0]
    metric = args.metric
    if "." in metric or "[]" in metric:
        dot_path = metric
    else:
        dot_path = find_shallowest_path(run.get("payload", {}), metric)
        if dot_path is None:
            console.print(f"[red]Could not locate key '{metric}' in the matching run.[/red]")
            sys.exit(1)
    show_blocks(run, dot_path)

def find_shallowest_path(payload: dict, key: str):
    def _walk(prefix, node, depth):
        if isinstance(node, dict):
            for k, v in node.items():
                path = f"{prefix}.{k}" if prefix else k
                if k == key and not isinstance(v, dict):
                    return (depth, path)
                if isinstance(v, dict):
                    res = _walk(path, v, depth+1)
                    if res:
                        return res
            return None
        return None
    result = _walk("", payload, 0)
    return result[1] if result else None

# ------------------------------------------------------------
# Interactive search loop
# ------------------------------------------------------------
def interactive_search(batch: str = None):
    console.print("\n[bold]Interactive Search[/bold]")
    while True:
        metrics = fetch_metrics(batch)
        if not metrics:
            console.print("[yellow]No metrics available yet. Ingest some runs first.[/yellow]")
            break

        # Build shorthand -> full paths map
        shorthand_map = build_shorthand_map(batch)

        selected_metric = None
        filtered = metrics[:]
        while True:
            if len(filtered) == 1:
                console.print(f"\n[green]Auto‑selected metric: {filtered[0]}[/green]")
                selected_metric = filtered[0]
                break
            if len(filtered) == 0:
                console.print("[red]No matching metrics. Try a different filter.[/red]")
                filtered = metrics[:]

            table = Table(title="Metrics (type to filter, press Enter for all)")
            table.add_column("#", justify="right")
            table.add_column("Metric Name", style="cyan")
            for i, m in enumerate(filtered, 1):
                table.add_row(str(i), m)
            console.print(table)

            query = Prompt.ask("Type to filter metrics (or 'all' to reset, number to select)", default="")

            # Explicit dot‑path / array‑path – accept directly
            if '.' in query or '[]' in query:
                selected_metric = query
                break

            # Shorthand with multiple possible full paths – show sub‑list
            if query in shorthand_map:
                paths = shorthand_map[query]
                if len(paths) == 1:
                    selected_metric = paths[0]
                    break
                else:
                    sub_table = Table(title=f"Select full path for '{query}'")
                    sub_table.add_column("#", justify="right")
                    sub_table.add_column("Full Path", style="cyan")
                    for i, p in enumerate(paths, 1):
                        sub_table.add_row(str(i), p)
                    console.print(sub_table)
                    sub_choice = Prompt.ask("Choose a path", default="1")
                    if sub_choice.isdigit():
                        idx = int(sub_choice) - 1
                        if 0 <= idx < len(paths):
                            selected_metric = paths[idx]
                            break

            if query.lower() == "all":
                filtered = metrics[:]
                continue
            if query.isdigit():
                idx = int(query) - 1
                if 0 <= idx < len(filtered):
                    selected_metric = filtered[idx]
                    break
            filtered = [m for m in metrics if query.lower() in m.lower()]

        metric = selected_metric

        console.print("\nOperators:")
        console.print("  >  (greater than)")
        console.print("  >= (greater than or equal)")
        console.print("  <  (less than)")
        console.print("  <= (less than or equal)")
        console.print("  =  (equal)")
        op_map = {
            ">": "gt", ">=": "gte", "<": "lt", "<=": "lte", "=": "eq",
            "gt": "gt", "gte": "gte", "lt": "lt", "lte": "lte", "eq": "eq",
        }
        op_input = Prompt.ask("Operator", choices=list(op_map.keys()), default=">")
        op = op_map[op_input]
        value = Prompt.ask("Value")

        page = 0
        while True:
            data = perform_search(metric=metric, op=op, value=value, batch=batch, page=page)
            if data is None:
                break
            display_results(data)
            content = data.get("content", [])
            total_pages = data.get("totalPages", 1)
            if not content or total_pages <= 1:
                break
            cmd = Prompt.ask("Navigation (n/p/q)", choices=["n", "p", "q"], default="q")
            if cmd == "n" and page < total_pages - 1:
                page += 1
            elif cmd == "p" and page > 0:
                page -= 1
            elif cmd == "q":
                break

        again = Prompt.ask("Another search? (y/n)", choices=["y", "n"], default="n")
        if again != "y":
            break

def display_results(data):
    if data is None:
        return
    content = data.get("content", [])
    total_elements = data.get("totalElements", 0)
    total_pages = data.get("totalPages", 1)
    page_number = data.get("pageable", {}).get("pageNumber", 0)

    if not content:
        console.print("No matching runs found.")
        return

    table = Table(title=f"Search Results (page {page_number+1}/{total_pages})")
    table.add_column("ID", justify="right", style="cyan")
    table.add_column("Created At", justify="left", style="green")
    table.add_column("Experiment / Source", justify="left")
    table.add_column("Snippet", justify="left", no_wrap=False, max_width=60)

    for run in content:
        run_id = run["id"]
        created = run["createdAt"]
        payload = run.get("payload", {})
        experiment = payload.get("experiment", "")
        source = payload.get("_source", {}).get("file", "")
        description = experiment or source or "-"
        snippet = json.dumps(payload, indent=2)
        if len(snippet) > 120:
            snippet = snippet[:120] + "..."
        table.add_row(str(run_id), created, description, snippet)

    console.print(table)
    if total_pages > 1:
        nav = f"Page {page_number+1} of {total_pages} | n = next page, p = previous page, q = quit"
        console.print(nav)

# ------------------------------------------------------------
# Status
# ------------------------------------------------------------
def show_status():
    if backend_running():
        console.print("[green]RunLedger backend is running.[/green]")
        try:
            resp = requests.get(f"{API_BASE}/metrics")
            if resp.status_code == 200:
                metrics = resp.json()
                console.print(f"Available metrics across all batches: {', '.join(metrics) if metrics else 'none'}")
        except Exception:
            pass
    else:
        console.print("[red]RunLedger backend is not running.[/red]")

# ------------------------------------------------------------
# Diff
# ------------------------------------------------------------
def cli_diff(args):
    if args.history:
        batch = args.batch
        if not batch:
            console.print("[red]--batch is required with --history[/red]")
            sys.exit(1)

        resp = requests.get(f"{API_BASE}/{args.id1}")
        if resp.status_code != 200:
            console.print("[red]Could not fetch run. Check ID.[/red]")
            sys.exit(1)
        run = resp.json()
        payload = run.get("payload", {})
        source_file = payload.get("_source", {}).get("file", "")
        source_index = payload.get("_source", {}).get("index", 0)

        resp = requests.get(f"{API_BASE}/versions", params={
            "batch": batch, "sourceFile": source_file, "sourceIndex": source_index
        })
        if resp.status_code != 200 or not resp.json():
            console.print("[red]No versions found.[/red]")
            sys.exit(1)
        versions = resp.json()

        if len(versions) < 2:
            console.print("[yellow]Only one version exists – nothing to diff.[/yellow]")
            return

        for i in range(len(versions) - 1):
            v1 = versions[i]
            v2 = versions[i+1]
            flat1 = flatten_payload(v1.get("payload", {}))
            flat2 = flatten_payload(v2.get("payload", {}))
            all_keys = sorted(set(flat1) | set(flat2))

            table = Table(title=f"Version Diff: v{v1['id']} → v{v2['id']}")
            table.add_column("Metric / Parameter", style="cyan")
            table.add_column(f"v{v1['id']}", style="white")
            table.add_column(f"v{v2['id']}", style="white")
            table.add_column("Δ", style="white")

            for key in all_keys:
                v1_val = flat1.get(key, "—")
                v2_val = flat2.get(key, "—")
                delta_str, color = compute_delta(v1_val, v2_val)
                style_prefix = f"[{color}]" if color != "white" else ""
                style_suffix = "[/]" if style_prefix else ""
                table.add_row(
                    f"{style_prefix}{key}{style_suffix}",
                    f"{style_prefix}{v1_val}{style_suffix}",
                    f"{style_prefix}{v2_val}{style_suffix}",
                    f"{style_prefix}{delta_str}{style_suffix}"
                )
            console.print(table)
        return

    if args.id2 is None:
        console.print("[red]Second run ID is required (or use --history).[/red]")
        sys.exit(1)

    resp1 = requests.get(f"{API_BASE}/{args.id1}")
    resp2 = requests.get(f"{API_BASE}/{args.id2}")
    if resp1.status_code != 200 or resp2.status_code != 200:
        console.print("[red]Could not fetch one or both runs. Check IDs.[/red]")
        sys.exit(1)

    run1 = resp1.json()
    run2 = resp2.json()
    flat1 = flatten_payload(run1.get("payload", {}))
    flat2 = flatten_payload(run2.get("payload", {}))

    all_keys = sorted(set(flat1) | set(flat2))

    if args.json:
        diff_data = []
        for key in all_keys:
            v1 = flat1.get(key, None)
            v2 = flat2.get(key, None)
            delta_str, _ = compute_delta(v1, v2)
            diff_data.append({"path": key, "run1": v1, "run2": v2, "delta": delta_str})
        print(json.dumps(diff_data, indent=2))
        return

    table = Table(title=f"Run Diff: {args.id1} vs {args.id2}")
    table.add_column("Metric / Parameter", style="cyan")
    table.add_column(f"Run {args.id1}", style="white")
    table.add_column(f"Run {args.id2}", style="white")
    table.add_column("Δ", style="white")

    for key in all_keys:
        v1 = flat1.get(key, "—")
        v2 = flat2.get(key, "—")
        delta_str, color = compute_delta(v1, v2)
        style_prefix = f"[{color}]" if color != "white" else ""
        style_suffix = "[/]" if style_prefix else ""
        table.add_row(
            f"{style_prefix}{key}{style_suffix}",
            f"{style_prefix}{v1}{style_suffix}",
            f"{style_prefix}{v2}{style_suffix}",
            f"{style_prefix}{delta_str}{style_suffix}"
        )

    console.print(table)

# ------------------------------------------------------------
# Aggregate
# ------------------------------------------------------------
def cli_aggregate(args):
    params = {"agg": args.agg, "metric": args.metric}
    if args.group_by:
        params["groupBy"] = args.group_by
    if args.batch:
        params["batch"] = args.batch

    resp = requests.get(f"{API_BASE}/aggregate", params=params)
    if resp.status_code != 200:
        console.print(f"[red]Aggregate failed: {resp.text}[/red]")
        sys.exit(1)

    data = resp.json()
    if args.json:
        print(json.dumps(data, indent=2))
        return

    if not data:
        console.print("[yellow]No results.[/yellow]")
        return

    table = Table(title=f"{args.agg}({args.metric})" +
                  (f" grouped by {args.group_by}" if args.group_by else ""))
    if args.group_by:
        table.add_column(args.group_by, style="cyan")
    table.add_column(args.agg, style="green", justify="right")

    for row in data:
        if args.group_by:
            table.add_row(str(row.get("group", "")), f"{row['result']:.6f}")
        else:
            table.add_row(f"{row['result']:.6f}")
    console.print(table)

# ------------------------------------------------------------
# Main entry point
# ------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="RunLedger CLI")
    subparsers = parser.add_subparsers(dest="command", help="Command")

    # scan
    scan_parser = subparsers.add_parser("scan", help="Scan a folder of JSON files")
    scan_parser.add_argument("folder", help="Path to the folder")
    scan_parser.add_argument("--batch", help="Custom batch name (default: folder name)")
    scan_parser.add_argument("--jsonl", action="store_true", help="Process files line by line (JSONL format)")

    # stop & status
    subparsers.add_parser("stop", help="Stop the RunLedger backend")
    subparsers.add_parser("status", help="Show backend status")

    # search – supports multiple --metric --op --value triples
    search_parser = subparsers.add_parser("search", help="Search runs")
    search_parser.add_argument("--metric", action='append', dest='metrics',
                               help="Metric key (can be repeated)")
    search_parser.add_argument("--op", action='append', dest='ops',
                               choices=["gt","gte","lt","lte","eq"],
                               help="Operator (can be repeated)")
    search_parser.add_argument("--value", action='append', dest='values',
                               help="Value to compare (can be repeated)")
    search_parser.add_argument("--combine", choices=["and","or"], default=None,
                               help="Combine multiple conditions with AND or OR")
    search_parser.add_argument("--q", help="Full‑text / fuzzy phrase")
    search_parser.add_argument("--fuzzy", action="store_true", help="Enable fuzzy search")
    search_parser.add_argument("--batch")
    search_parser.add_argument("--summary", action="store_true", help="Show only ID and source file")
    search_parser.add_argument("--json", action="store_true", help="Output raw JSON instead of block format")
    search_parser.add_argument("--block", type=int, help="Show only this block depth (default: all levels)")
    search_parser.add_argument("--pointers", dest="pointers", action="store_true", default=True,
                               help="Show exact match locations (default: True)")
    search_parser.add_argument("--no-pointers", dest="pointers", action="store_false",
                               help="Disable match pointers")
    search_parser.add_argument("--save", help="Save this search under a name")
    search_parser.add_argument("--saved", help="Run a previously saved search by name")

    # preview
    preview_parser = subparsers.add_parser("preview", help="Show block levels for a metric match")
    preview_parser.add_argument("--metric", required=True)
    preview_parser.add_argument("--op", required=True, choices=["gt","gte","lt","lte","eq"])
    preview_parser.add_argument("--value", required=True)
    preview_parser.add_argument("--batch")
    preview_parser.add_argument("--pointers", dest="pointers", action="store_true", default=True,
                                help="Show exact match locations (default: True)")
    preview_parser.add_argument("--no-pointers", dest="pointers", action="store_false",
                                help="Disable match pointers")

    # export – supports same multi‑filter as search
    export_parser = subparsers.add_parser("export", help="Export search results to a file")
    export_parser.add_argument("--metric", action='append', dest='metrics',
                               help="Metric key (can be repeated)")
    export_parser.add_argument("--op", action='append', dest='ops',
                               choices=["gt","gte","lt","lte","eq"],
                               help="Operator (can be repeated)")
    export_parser.add_argument("--value", action='append', dest='values',
                               help="Value to compare (can be repeated)")
    export_parser.add_argument("--combine", choices=["and","or"], default=None,
                               help="Combine multiple conditions with AND or OR")
    export_parser.add_argument("--q", help="Full‑text / fuzzy phrase")
    export_parser.add_argument("--fuzzy", action="store_true", help="Enable fuzzy search")
    export_parser.add_argument("--batch")
    export_parser.add_argument("--block", type=int, help="Block depth to export (default: all levels)")
    export_parser.add_argument("--format", choices=["text","json"], default="text",
                               help="Output format (text or json)")
    export_parser.add_argument("--output", help="File to write to (default: stdout)")
    export_parser.add_argument("--pointers", dest="pointers", action="store_true", default=True,
                               help="Show exact match locations (default: True)")
    export_parser.add_argument("--no-pointers", dest="pointers", action="store_false",
                               help="Disable match pointers")

    # metrics
    metrics_parser = subparsers.add_parser("metrics", help="List available metric keys")
    metrics_parser.add_argument("--batch")
    metrics_parser.add_argument("--verbose", action="store_true", help="Show full dot‑path and depth")

    # interactive
    interactive_parser = subparsers.add_parser("interactive", help="Launch the interactive search wizard")
    interactive_parser.add_argument("--batch")

    # saved
    saved_parser = subparsers.add_parser("saved", help="Manage saved searches")
    saved_sub = saved_parser.add_subparsers(dest="saved_action")
    saved_list = saved_sub.add_parser("list", help="List saved searches")
    saved_list.add_argument("--batch")
    saved_delete = saved_sub.add_parser("delete", help="Delete a saved search")
    saved_delete.add_argument("name")
    saved_delete.add_argument("--batch")

    # diff
    diff_parser = subparsers.add_parser("diff", help="Compare two runs side‑by‑side")
    diff_parser.add_argument("id1", type=int, help="Run ID (or first run ID for pairwise diff)")
    diff_parser.add_argument("id2", type=int, nargs="?", default=None, help="Second run ID (optional if using --history)")
    diff_parser.add_argument("--json", action="store_true", help="Output raw JSON")
    diff_parser.add_argument("--history", action="store_true", help="Diff all versions of the same run identity")
    diff_parser.add_argument("--batch", help="Batch name (required with --history)")

    # aggregate
    agg_parser = subparsers.add_parser("aggregate", help="Aggregate metrics (AVG/MAX/MIN/SUM/COUNT)")
    agg_parser.add_argument("--metric", required=True, help="Metric path to aggregate")
    agg_parser.add_argument("--agg", required=True, choices=["AVG","MAX","MIN","SUM","COUNT"], help="Aggregate function")
    agg_parser.add_argument("--group-by", help="Field to group results by")
    agg_parser.add_argument("--batch", help="Batch name")
    agg_parser.add_argument("--json", action="store_true", help="Output raw JSON")

    # guided
    subparsers.add_parser("guided", help="Interactive guided experiment search")

    args = parser.parse_args()

    if not check_docker():
        sys.exit(1)

    if args.command is None:
        try:
            resp = requests.get(API_BASE, params={"size": 1})
            has_data = resp.status_code == 200 and resp.json().get("totalElements", 0) > 0
        except Exception:
            has_data = False

        if not has_data:
            ensure_backend()
            from cli.guided import run_guided_search
            run_guided_search()
        else:
            console.print("[bold cyan]RunLedger[/bold cyan] – zero‑friction experiment search")
            console.print("\nCommon commands:")
            console.print("  [bold]runledger scan <folder>[/bold]           Ingest experiment files")
            console.print("  [bold]runledger search --metric <key> --op <op> --value <v>[/bold]")
            console.print("  [bold]runledger guided[/bold]                 Interactive search wizard")
            console.print("\nUse [bold]runledger --help[/bold] for full command list.")
        return

    if args.command == "scan":
        if not backend_running():
            start_backend()
        else:
            console.print("Backend already running.")
        batch = args.batch if args.batch else Path(args.folder).name
        ingest_folder(args.folder, batch, jsonl=args.jsonl)

        console.print(f"\n[bold]Schema summary for batch '{batch}':[/bold]")
        verbose_metrics = fetch_verbose_metrics(batch)
        if not verbose_metrics:
            console.print("[yellow]No metrics were discovered.[/yellow]")
        else:
            table = Table(title="Key -> Resolved Path")
            table.add_column("Shorthand Key", style="cyan", no_wrap=True)
            table.add_column("Full Dot-Path", style="green")
            table.add_column("Depth", justify="right")
            for item in verbose_metrics:
                table.add_row(item["key"], item["path"], str(item["depth"]))
            console.print(table)

            deepest = max(verbose_metrics, key=lambda x: x["depth"])
            console.print(f"\n[bold]Block preview for deepest metric '{deepest['key']}':[/bold]")

            preview_run = None
            page = 0
            while True:
                resp = requests.get(API_BASE, params={"batch": batch, "size": 10, "page": page})
                if resp.status_code != 200:
                    break
                data = resp.json()
                if not data.get("content"):
                    break
                for run in data["content"]:
                    if navigate_json_pointer(run.get("payload", {}), "/" + deepest["path"].replace(".", "/").replace("[]","")):
                        preview_run = run
                        break
                if preview_run:
                    break
                page += 1
                if page >= 10:
                    break

            if preview_run:
                show_blocks(preview_run, deepest["path"])
            else:
                console.print("[yellow]No run in the batch contains this metric – preview skipped.[/yellow]")

            console.print("\n[dim]Tip: run `ledger preview --metric <key> --op <gt|lt|eq> --value <v> --batch <batch>` to explore block levels for any metric.[/dim]")

    elif args.command == "stop":
        stop_backend()
    elif args.command == "status":
        show_status()
    elif args.command == "search":
        cli_search(args)
    elif args.command == "metrics":
        cli_metrics(args)
    elif args.command == "preview":
        cli_preview(args)
    elif args.command == "export":
        cli_export(args)
    elif args.command == "interactive":
        if not backend_running():
            start_backend()
        interactive_search(args.batch)
    elif args.command == "saved":
        if args.saved_action == "list":
            searches = list_saved_searches(args.batch)
            if not searches:
                console.print("No saved searches.")
            else:
                table = Table(title="Saved Searches")
                table.add_column("Name")
                table.add_column("Batch")
                table.add_column("Created")
                for s in searches:
                    table.add_row(s["name"], s.get("batch", ""), s["createdAt"])
                console.print(table)
        elif args.saved_action == "delete":
            ok = delete_saved_search(args.name, args.batch)
            if ok:
                console.print(f"[green]Deleted saved search '{args.name}'.[/green]")
            else:
                console.print(f"[red]Saved search '{args.name}' not found.[/red]")
    elif args.command == "diff":
        cli_diff(args)
    elif args.command == "aggregate":
        cli_aggregate(args)
    elif args.command == "guided":
        ensure_backend()
        from cli.guided import run_guided_search
        run_guided_search()
    else:
        parser.print_help()

if __name__ == "__main__":
    main()