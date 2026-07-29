#!/usr/bin/env python3
"""RunLedger – interactive guided experiment search."""

import json
import time
import sys
from pathlib import Path

import requests
from rich.console import Console
from rich.panel import Panel
from rich.progress import Progress
from rich.prompt import Prompt, Confirm
from rich.table import Table

# ------------------------------------------------------------
# Configuration – must match backend settings
# ------------------------------------------------------------
API_BASE   = "http://localhost:8080/api/runs"
SAVED_API  = "http://localhost:8080/api/saved"
HEALTH_URL = "http://localhost:8080/actuator/health"

console = Console()

# ------------------------------------------------------------
# Tiny helpers (self‑contained; do NOT import ledger.py)
# ------------------------------------------------------------
def backend_ready():
    try:
        return requests.get(HEALTH_URL, timeout=2).status_code == 200
    except Exception:
        return False

def wait_for_backend(timeout=60):
    console.print("[dim]Waiting for backend...[/dim]", end="")
    start = time.time()
    while time.time() - start < timeout:
        if backend_ready():
            console.print("[green] ready![/green]")
            return True
        time.sleep(2)
        console.print(".", end="")
    console.print("\n[red]Backend did not start in time.[/red]")
    return False

def ingest_folder(folder, batch):
    """Scan a folder using the API, showing a progress bar."""
    path = Path(folder).expanduser()
    if not path.exists():
        console.print(f"[red]Folder not found: {folder}[/red]")
        return 0

    json_files = list(path.glob("*.json")) + list(path.glob("*.jsonl"))
    if not json_files:
        console.print(f"[yellow]No JSON/JSONL files found in {folder}[/yellow]")
        return 0

    console.print(f"Found {len(json_files)} file(s). Ingesting into batch '{batch}'...")
    count = 0
    with Progress() as progress:
        task = progress.add_task("[cyan]Ingesting...", total=len(json_files))
        for jfile in json_files:
            progress.update(task, description=f"Processing {jfile.name}...")
            try:
                with open(jfile, "r", encoding="utf-8") as fh:
                    # Decide if it's JSONL by extension
                    if jfile.suffix == ".jsonl":
                        for idx, line in enumerate(fh):
                            line = line.strip()
                            if not line:
                                continue
                            try:
                                run = json.loads(line)
                            except json.JSONDecodeError as e:
                                console.print(f"[red]Skipping {jfile.name} line {idx+1}: {e}[/red]")
                                continue
                            payload = {"payload": run, "batch": batch}
                            payload["payload"]["_source"] = {"file": jfile.name, "index": idx}
                            try:
                                resp = requests.post(API_BASE, json=payload)
                                if resp.status_code == 201:
                                    count += 1
                                else:
                                    console.print(f"[red]Failed to ingest line {idx+1} from {jfile.name}: {resp.status_code}[/red]")
                            except requests.RequestException as e:
                                console.print(f"[red]Error sending {jfile.name}: {e}[/red]")
                    else:
                        data = json.load(fh)
                        if isinstance(data, dict):
                            data = [data]
                        for idx, run in enumerate(data):
                            payload = {"payload": run, "batch": batch}
                            payload["payload"]["_source"] = {"file": jfile.name, "index": idx}
                            try:
                                resp = requests.post(API_BASE, json=payload)
                                if resp.status_code == 201:
                                    count += 1
                                else:
                                    console.print(f"[red]Failed to ingest run {idx} from {jfile.name}: {resp.status_code}[/red]")
                            except requests.RequestException as e:
                                console.print(f"[red]Error sending {jfile.name}: {e}[/red]")
            except Exception as e:
                console.print(f"[red]Skipping {jfile.name}: {e}[/red]")
            progress.advance(task)
    console.print(f"[green]Successfully ingested {count} run(s) into batch '{batch}'.[/green]")
    return count

def fetch_verbose_metrics(batch):
    params = {"batch": batch, "verbose": "true"}
    resp = requests.get(f"{API_BASE}/metrics", params=params)
    if resp.status_code == 200:
        return resp.json()
    return []

def perform_search(metric=None, op=None, value=None, q=None, fuzzy=False, batch=None, pointers=True, block=None):
    params = {"page": 0, "size": 50, "sort": "created_at,desc"}
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
        if block is not None:
            params["block"] = block
    try:
        resp = requests.get(API_BASE, params=params)
        if resp.status_code == 200:
            return resp.json()
    except Exception:
        pass
    return None

def perform_multi_search(filters, combine, batch=None, block=None):
    body = {"filters": filters, "combine": combine}
    if batch:
        body["batch"] = batch
    if block is not None:
        body["block"] = block
    try:
        resp = requests.post(f"{API_BASE}/search", json=body)
        if resp.status_code == 200:
            return resp.json()
    except Exception:
        pass
    return None

def save_search(name, batch, params):
    body = {"name": name, "batch": batch, "paramsJson": json.dumps(params)}
    resp = requests.post(SAVED_API, json=body)
    if resp.status_code == 200:
        return True
    console.print(f"[red]Failed to save search: {resp.text}[/red]")
    return False

def show_blocks(run: dict, dot_path: str = None, block_levels: list = None):
    """Simplified block display (identical to ledger.py's version)."""
    payload = run.get("payload", {})
    if isinstance(payload, dict):
        source_file = payload.get("_source", {}).get("file") or payload.get("experiment") or f"run-{run['id']}"
    else:
        source_file = f"run-{run['id']}"

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
        try:
            pretty = json.dumps(payload, indent=2)
        except Exception:
            pretty = str(payload)
        console.print(Panel(pretty, title=f"Run {run['id']} · {source_file}"))
        return

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

# ------------------------------------------------------------
# The guided flow itself
# ------------------------------------------------------------
def run_guided_search():
    console.print(Panel("[bold cyan]RunLedger – Interactive Experiment Search[/bold cyan]\n"
                         "Answer a few questions and we'll find your best experiments.",
                         title="Welcome"))

    # ── Step 1: Folder & Batch ──
    console.print("\n[bold][Step 1/5] Select your experiment folder[/bold]")
    folder = Prompt.ask("Folder path (drag‑and‑drop or type)",
                        default="")
    batch  = Prompt.ask("Batch name (optional, default: folder name)", default="")
    if not batch:
        batch = Path(folder).name

    # ── Step 2: Scan if needed ──
    console.print(f"\n[bold][Step 2/5] Ingest data from '{batch}'[/bold]")
    metrics = fetch_verbose_metrics(batch)
    if metrics:
        console.print(f"[green]Batch '{batch}' already has {len(metrics)} metrics. Skipping scan.[/green]")
    else:
        console.print(f"\n[bold]No existing data for batch '{batch}'. Scanning...[/bold]")
        ingested = ingest_folder(folder, batch)
        if ingested == 0:
            console.print("[red]No runs were ingested. Exiting.[/red]")
            return
        metrics = fetch_verbose_metrics(batch)
        if not metrics:
            console.print("[red]Metric discovery failed. Exiting.[/red]")
            return

    # Show schema summary table
    table = Table(title="Available Metrics")
    table.add_column("#", justify="right")
    table.add_column("Metric", style="cyan")
    table.add_column("Full Path", style="dim")
    for i, m in enumerate(metrics, 1):
        table.add_row(str(i), m["key"], m["path"])
    console.print(table)

    # ── Step 3: Build conditions ──
    console.print(f"\n[bold][Step 3/5] Define your search conditions[/bold]")
    filters = []
    combine = "and"
    first_condition = True

    while True:
        # Metric selection (filter‑as‑you‑type)
        metric_names = [m["key"] for m in metrics]
        selected_metric = None
        filtered = metric_names[:]
        while True:
            if len(filtered) == 1:
                console.print(f"\n[green]Auto‑selected metric: {filtered[0]}[/green]")
                selected_metric = filtered[0]
                break
            if len(filtered) == 0:
                console.print("[red]No matching metrics. Try a different filter.[/red]")
                filtered = metric_names[:]

            ftable = Table(title="Filter Metrics (type to narrow)")
            ftable.add_column("#", justify="right")
            ftable.add_column("Metric", style="cyan")
            for i, m in enumerate(filtered, 1):
                ftable.add_row(str(i), m)
            console.print(ftable)

            query = Prompt.ask("Type to filter metrics (or 'all' to reset, number to select)", default="")
            if query.lower() == "all":
                filtered = metric_names[:]
                continue
            if query.isdigit():
                idx = int(query) - 1
                if 0 <= idx < len(filtered):
                    selected_metric = filtered[idx]
                    break
            filtered = [m for m in metric_names if query.lower() in m.lower()]

        # Operator
        console.print("\n[bold]Select operator:[/bold]")
        console.print("  >  (greater than)")
        console.print("  >= (greater than or equal)")
        console.print("  <  (less than)")
        console.print("  <= (less than or equal)")
        console.print("  =  (equal)")
        op_map = {">": "gt", ">=": "gte", "<": "lt", "<=": "lte", "=": "eq",
                  "gt": "gt", "gte": "gte", "lt": "lt", "lte": "lte", "eq": "eq"}
        op_input = Prompt.ask("Operator", choices=list(op_map.keys()), default=">")
        op = op_map[op_input]

        # Value
        value = Prompt.ask("Value")

        filters.append({"metric": selected_metric, "op": op, "value": value})

        # Show current conditions
        conds = " [bold yellow]" + f" {combine.upper()} ".join(f"{f['metric']} {f['op']} {f['value']}" for f in filters) + "[/bold yellow]"
        console.print(f"\n[bold]Current filter(s):[/bold]{conds}")

        if first_condition and len(metric_names) > 1:
            another = Confirm.ask("Add another condition?", default=False)
            if not another:
                break
            if len(filters) >= 2:
                combine = Prompt.ask("Combine with", choices=["and", "or"], default="and")
        else:
            break
        first_condition = False

    # ── Step 4: Block depth ──
    console.print(f"\n[bold][Step 4/5] Choose output detail[/bold]")
    block = None
    if Confirm.ask("Limit output to a specific block depth? (block‑0 = tightest match)", default=False):
        block = Prompt.ask("Block depth", default="0")
        block = int(block)

    # ── Step 5: Search & Results ──
    console.print(f"\n[bold][Step 5/5] Searching...[/bold]")
    if len(filters) == 1:
        f = filters[0]
        data = perform_search(metric=f["metric"], op=f["op"], value=f["value"], batch=batch, pointers=True, block=block)
    else:
        data = perform_multi_search(filters, combine, batch=batch, block=block)

    if data is None or not data.get("content"):
        console.print("[yellow]No matching runs found.[/yellow]")
    else:
        for run in data["content"]:
            show_blocks(run, filters[0]["metric"] if len(filters) == 1 else None, [block] if block is not None else None)

    # ── Post‑search menu ──
    while True:
        console.print("\n[bold]What would you like to do next?[/bold]")
        console.print("  [1] Save this search")
        console.print("  [2] Export results (text)")
        console.print("  [3] Run another search")
        console.print("  [4] Compare two runs (diff)")
        console.print("  [5] Exit")
        choice = Prompt.ask("Enter choice", choices=["1","2","3","4","5"], default="5")

        if choice == "1":
            name = Prompt.ask("Name for this search")
            params = {"metrics": [f["metric"] for f in filters],
                      "ops": [f["op"] for f in filters],
                      "values": [f["value"] for f in filters],
                      "combine": combine, "batch": batch}
            if save_search(name, batch, params):
                console.print(f"[green]Saved search '{name}'.[/green]")
        elif choice == "2":
            # Simple text export to a file
            out_file = Prompt.ask("Output file path", default="runledger_export.txt")
            if data and data.get("content"):
                from ledgger import build_export_blocks, format_blocks_as_text
                blocks = build_export_blocks(data["content"], filters[0]["metric"] if len(filters) == 1 else None, [block] if block is not None else None, include_pointers=True)
                Path(out_file).write_text(format_blocks_as_text(blocks), encoding="utf-8")
                console.print(f"[green]Exported {len(blocks)} block(s) to {out_file}[/green]")
            else:
                console.print("[yellow]No data to export.[/yellow]")
        elif choice == "3":
            # Restart the guided flow
            run_guided_search()
            return
        elif choice == "4":
            id1 = Prompt.ask("First Run ID")
            id2 = Prompt.ask("Second Run ID")
            # Quick diff using the API
            resp1 = requests.get(f"{API_BASE}/{id1}")
            resp2 = requests.get(f"{API_BASE}/{id2}")
            if resp1.status_code == 200 and resp2.status_code == 200:
                run1 = resp1.json()
                run2 = resp2.json()
                flat1 = flatten_payload(run1.get("payload", {}))
                flat2 = flatten_payload(run2.get("payload", {}))
                all_keys = sorted(set(flat1) | set(flat2))
                table = Table(title=f"Run Diff: {id1} vs {id2}")
                table.add_column("Metric", style="cyan")
                table.add_column(f"Run {id1}", style="white")
                table.add_column(f"Run {id2}", style="white")
                table.add_column("Δ", style="white")
                for key in all_keys:
                    v1 = flat1.get(key, "—")
                    v2 = flat2.get(key, "—")
                    delta_str, color = compute_delta(v1, v2)
                    style_prefix = f"[{color}]" if color != "white" else ""
                    style_suffix = "[/]" if style_prefix else ""
                    table.add_row(f"{style_prefix}{key}{style_suffix}",
                                  f"{style_prefix}{v1}{style_suffix}",
                                  f"{style_prefix}{v2}{style_suffix}",
                                  f"{style_prefix}{delta_str}{style_suffix}")
                console.print(table)
            else:
                console.print("[red]Could not fetch one or both runs. Check IDs.[/red]")
        elif choice == "5":
            break

    # Print the equivalent CLI command
    cli_cmd = "ledger search"
    for f in filters:
        cli_cmd += f" --metric {f['metric']} --op {f['op']} --value {f['value']}"
    if len(filters) > 1:
        cli_cmd += f" --combine {combine}"
    if batch:
        cli_cmd += f" --batch {batch}"
    if block is not None:
        cli_cmd += f" --block {block}"
    console.print(f"\n[dim]Tip: next time you can run this search directly with:[/dim]\n[bold cyan]{cli_cmd}[/bold cyan]")