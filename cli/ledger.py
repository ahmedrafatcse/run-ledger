#!/usr/bin/env python3
"""
RunLedger CLI – zero‑friction experiment search.

Usage:
    python ledger.py scan /path/to/folder [--batch NAME]
    python ledger.py search --metric <key> --op <gt|lt|eq|...> --value <v> [--batch NAME] [--summary]
    python ledger.py search --metric <key1> --op <op1> --value <v1> --metric <key2> --op <op2> --value <v2> [--combine and|or]
    python ledger.py search --q <phrase> [--fuzzy] [--batch NAME] [--summary]
    python ledger.py export ... (same filters as search)
    python ledger.py preview --metric <key> --op <gt|lt|eq|...> --value <v> [--batch NAME]
    python ledger.py metrics [--batch NAME]
    python ledger.py interactive [--batch NAME]
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

import requests
from rich.console import Console
from rich.panel import Panel
from rich.prompt import Prompt
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
API_BASE = "http://localhost:8080/api/runs"
COMPOSE_FILE = os.path.join(os.path.dirname(__file__), "..", "docker-compose.yml")
HEALTH_URL = "http://localhost:8080/actuator/health"
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
    if not os.path.exists(COMPOSE_FILE):
        console.print("[red]docker-compose.yml not found. Are you in the project root?[/red]")
        sys.exit(1)
    console.print("Starting RunLedger backend...")
    subprocess.run(f"docker compose -f {COMPOSE_FILE} up -d", shell=True, check=True)
    if wait_for_backend():
        console.print("[green]RunLedger backend is running.[/green]")
    else:
        console.print("[red]Failed to start RunLedger. Check Docker logs.[/red]")
        sys.exit(1)

def stop_backend():
    if os.path.exists(COMPOSE_FILE):
        subprocess.run(f"docker compose -f {COMPOSE_FILE} down", shell=True, check=True)
        console.print("RunLedger backend stopped.")
    else:
        console.print("[red]Cannot find docker-compose.yml[/red]")

# ------------------------------------------------------------
# Ingestion
# ------------------------------------------------------------
def ingest_folder(folder: str, batch: str = None):
    path = Path(folder).expanduser()
    if not path.exists():
        console.print(f"[red]Folder not found: {folder}[/red]")
        return

    if batch is None:
        batch = path.name

    json_files = list(path.glob("*.json"))
    if not json_files:
        console.print(f"[yellow]No .json files found in {folder}[/yellow]")
        return

    console.print(f"Found {len(json_files)} JSON file(s) in '{batch}'. Ingesting...")
    count = 0
    for jfile in json_files:
        try:
            with open(jfile, "r") as fh:
                data = json.load(fh)
        except Exception as e:
            console.print(f"[red]Skipping {jfile.name}: {e}[/red]")
            continue

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

    console.print(f"[green]Successfully ingested {count} run(s) into batch '{batch}'.[/green]")

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
    # Guard against non‑dict payloads
    if isinstance(payload, dict):
        source_file = payload.get("_source", {}).get("file", "unknown")
    else:
        source_file = "unknown"

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
            # For array paths with block=0, show each matched snippet individually
            for i, m in enumerate(matched):
                snippet = m.get("snippet")
                try:
                    pretty = json.dumps(snippet, indent=2)
                except Exception:
                    pretty = str(snippet)
                title = f"Run {run['id']} · {source_file}  |  metric: {dot_path}  |  depth: 0 (match {i+1}/{len(matched)})"
                console.print(Panel(pretty, title=title))
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
            source_file = payload.get("_source", {}).get("file", "unknown")
        else:
            source_file = "unknown"

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
                # One block per matched snippet
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

        # Print pointer and value if present (for block‑0 snippets)
        pointer = block.get("pointer")
        if pointer:
            val = block.get("value")
            if isinstance(val, float):
                val_str = f"{val:.6f}"
            else:
                val_str = str(val)
            lines.append(f"match: {pointer} = {val_str}")

        # Print all matched pointers if present (for non‑0 blocks with multiple matches)
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

# ------------------------------------------------------------
# Non‑interactive CLI outputs
# ------------------------------------------------------------
def cli_metrics(args):
    keys = fetch_metrics(args.batch)
    print(json.dumps(keys))

def cli_search(args):
    # Build filters list if --metric was provided
    filters = None
    if args.metrics and args.ops and args.values:
        if len(args.metrics) != len(args.ops) or len(args.metrics) != len(args.values):
            console.print("[red]Number of --metric, --op, --value arguments must match.[/red]")
            sys.exit(1)
        filters = [
            {"metric": m, "op": op, "value": v}
            for m, op, v in zip(args.metrics, args.ops, args.values)
        ]

    # Determine if pointers are requested
    pointers_enabled = getattr(args, "pointers", True)  # default True

    # Determine combine (only used for multi‑filter)
    combine = args.combine if args.combine else "and"

    # Decide which search to perform
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

    # 1. Summary mode
    if args.summary:
        content = data.get("content", [])
        if not content:
            print("No matching runs found.")
            return
        table = Table(title="Search Results (summary)")
        table.add_column("ID", justify="right", style="cyan")
        table.add_column("Source File", justify="left", style="green")
        for run in content:
            run_id = run["id"]
            source_file = run.get("payload", {}).get("_source", {}).get("file", "unknown")
            table.add_row(str(run_id), source_file)
        console.print(table)
        return

    # 2. Explicit JSON output
    if args.json:
        print(json.dumps(data, indent=2))
        return

    # 3. Default: block‑structured output
    content = data.get("content", [])
    if not content:
        console.print("No matching runs found.")
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
# Interactive search loop (unchanged)
# ------------------------------------------------------------
def interactive_search(batch: str = None):
    console.print("\n[bold]Interactive Search[/bold]")
    while True:
        metrics = fetch_metrics(batch)
        if not metrics:
            console.print("[yellow]No metrics available yet. Ingest some runs first.[/yellow]")
            break

        console.print("\nAvailable metrics:")
        for i, m in enumerate(metrics, 1):
            console.print(f"  {i}. {m}")
        choice = Prompt.ask("Select a metric (number or name)", default="1")
        if choice.isdigit():
            idx = int(choice) - 1
            if 0 <= idx < len(metrics):
                metric = metrics[idx]
            else:
                console.print("[red]Invalid selection.[/red]")
                continue
        else:
            if choice in metrics:
                metric = choice
            else:
                console.print("[red]Metric not found.[/red]")
                continue

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
            data = perform_search(metric=metric, op=op, value=value,
                                  batch=batch, page=page)
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
# Main entry point
# ------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="RunLedger CLI")
    subparsers = parser.add_subparsers(dest="command", help="Command")

    # scan
    scan_parser = subparsers.add_parser("scan", help="Scan a folder of JSON files")
    scan_parser.add_argument("folder", help="Path to the folder")
    scan_parser.add_argument("--batch", help="Custom batch name (default: folder name)")

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
    # Pointers flag: on by default, use --no-pointers to disable
    search_parser.add_argument("--pointers", dest="pointers", action="store_true", default=True,
                               help="Show exact match locations (default: True)")
    search_parser.add_argument("--no-pointers", dest="pointers", action="store_false",
                               help="Disable match pointers")

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

    # interactive
    interactive_parser = subparsers.add_parser("interactive", help="Launch the interactive search wizard")
    interactive_parser.add_argument("--batch")

    args = parser.parse_args()

    if not check_docker():
        sys.exit(1)

    if args.command == "scan":
        if not backend_running():
            start_backend()
        else:
            console.print("Backend already running.")
        batch = args.batch if args.batch else Path(args.folder).name
        ingest_folder(args.folder, batch)

        # Schema summary
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
            try:
                resp = requests.get(API_BASE, params={"batch": batch, "size": 1})
                if resp.status_code == 200:
                    data = resp.json()
                    if data.get("content"):
                        run = data["content"][0]
                        dot_path = deepest["path"]
                        show_blocks(run, dot_path)
                    else:
                        console.print("[yellow]No runs in batch – cannot preview.[/yellow]")
                else:
                    console.print(f"[red]Could not fetch run for preview (HTTP {resp.status_code}).[/red]")
            except Exception as e:
                console.print(f"[red]Could not auto‑preview: {e}[/red]")

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
    else:
        parser.print_help()

if __name__ == "__main__":
    main()