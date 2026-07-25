#!/usr/bin/env python3
"""
RunLedger CLI – zero‑friction experiment search.

Usage:
    python ledger.py scan /path/to/folder [--batch NAME]
    python ledger.py search --metric <key> --op <gt|lt|eq|...> --value <v> [--batch NAME] [--summary]
    python ledger.py search --q <phrase> [--fuzzy] [--batch NAME] [--summary]
    python ledger.py metrics [--batch NAME]
    python ledger.py interactive [--batch NAME]   (human‑friendly search loop)
    python ledger.py stop
    python ledger.py status
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import requests
from rich.console import Console
from rich.prompt import Prompt
from rich.table import Table

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
# Ingestion (non‑interactive)
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
# API wrappers (shared by interactive & CLI)
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

def perform_search(metric=None, op=None, value=None, q=None, fuzzy=False,
                   batch=None, page=0, size=PAGE_SIZE):
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

# ------------------------------------------------------------
# Non‑interactive CLI outputs
# ------------------------------------------------------------
def cli_metrics(args):
    keys = fetch_metrics(args.batch)
    print(json.dumps(keys))

def cli_search(args):
    # Use new full‑text/fuzzy params if provided
    if args.q:
        data = perform_search(q=args.q, fuzzy=args.fuzzy,
                              batch=args.batch, page=0)
    else:
        data = perform_search(metric=args.metric, op=args.op,
                              value=args.value, batch=args.batch, page=0)

    if data is None:
        sys.exit(1)
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
    else:
        print(json.dumps(data, indent=2))

# ------------------------------------------------------------
# Interactive search loop (human use)
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

    # scan – ingest only, non‑interactive
    scan_parser = subparsers.add_parser("scan", help="Scan a folder of JSON files (ingest only, no prompt)")
    scan_parser.add_argument("folder", help="Path to the folder")
    scan_parser.add_argument("--batch", help="Custom batch name (default: folder name)")

    # stop & status
    subparsers.add_parser("stop", help="Stop the RunLedger backend")
    subparsers.add_parser("status", help="Show backend status")

    # search – supports both block‑search and full‑text/fuzzy
    search_parser = subparsers.add_parser("search", help="Perform a block search or full‑text/fuzzy search")
    search_parser.add_argument("--metric", help="Metric key (for block search)")
    search_parser.add_argument("--op", choices=["gt","gte","lt","lte","eq"], help="Operator (for block search)")
    search_parser.add_argument("--value", help="Value to compare (for block search)")
    search_parser.add_argument("--q", help="Full‑text or fuzzy search phrase")
    search_parser.add_argument("--fuzzy", action="store_true", help="Enable fuzzy search (requires --q)")
    search_parser.add_argument("--batch", help="Batch name")
    search_parser.add_argument("--summary", action="store_true", help="Show only ID and source file")

    # metrics – non‑interactive, prints JSON list of keys
    metrics_parser = subparsers.add_parser("metrics", help="List available metric keys as JSON")
    metrics_parser.add_argument("--batch")

    # interactive – the human‑friendly search loop
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
    elif args.command == "stop":
        stop_backend()
    elif args.command == "status":
        show_status()
    elif args.command == "search":
        cli_search(args)
    elif args.command == "metrics":
        cli_metrics(args)
    elif args.command == "interactive":
        if not backend_running():
            start_backend()
        interactive_search(args.batch)
    else:
        parser.print_help()

if __name__ == "__main__":
    main()