"""
Feature showcase tests using the real experiment data (test-data/results).

Run with:
    python -m pytest cli/tests/test_real_results.py -v -s

Assumes the RunLedger backend is already running (docker compose up -d).
"""

import json
import subprocess
import time
import uuid
from pathlib import Path

import pytest
import requests

# ------------------------------------------------------------
# Configuration
# ------------------------------------------------------------
PROJECT_ROOT = Path(__file__).parent.parent.parent   # run-ledger/
HEALTH_URL = "http://localhost:8080/actuator/health"
API_BASE  = "http://localhost:8080/api/runs"
DATA_DIR  = PROJECT_ROOT / "test-data" / "results"
BATCH     = f"real-results-{uuid.uuid4().hex[:8]}"

# ------------------------------------------------------------
# Helpers
# ------------------------------------------------------------
def run_cli(*args):
    cmd = ["python", str(PROJECT_ROOT / "cli" / "ledger.py"), *args]
    # Use encoding='utf-8' so the subprocess output is correctly decoded
    return subprocess.run(cmd, capture_output=True, encoding='utf-8', cwd=str(PROJECT_ROOT))

# ------------------------------------------------------------
# Fixture – ingest once per session
# ------------------------------------------------------------
@pytest.fixture(scope="session")
def setup_data():
    if not DATA_DIR.exists():
        pytest.skip(f"Folder {DATA_DIR} not found – place real experiment JSONs there first.")

    # Wait for backend
    for _ in range(30):
        try:
            if requests.get(HEALTH_URL, timeout=2).status_code == 200:
                break
        except Exception:
            pass
        time.sleep(2)
    else:
        pytest.fail("Backend not reachable (docker compose up -d)")

    result = run_cli("scan", str(DATA_DIR), "--batch", BATCH)
    assert result.returncode == 0, result.stderr
    assert "Successfully ingested" in result.stdout

# ------------------------------------------------------------
# v1 – Metric discovery (list every available key)
# ------------------------------------------------------------
def test_metric_discovery(setup_data):
    result = run_cli("metrics", "--batch", BATCH)
    assert result.returncode == 0, result.stderr
    keys = json.loads(result.stdout)
    assert len(keys) > 10, "Expected many distinct keys across the 60 real files"

    # Keys that exist outside arrays in your real data (therefore discoverable)
    must_have = ["script", "full_cacc", "full_asr", "pretrained_name", "target_label", "device"]
    for k in must_have:
        assert k in keys, f"Expected key '{k}' to be discovered"
    print("✅ Metric discovery --- SUCCESS")

# ------------------------------------------------------------
# v1 – Numeric block search
# ------------------------------------------------------------
def test_numeric_search(setup_data):
    query = "full_cacc > 0.5"
    result = run_cli("search", "--metric", "full_cacc", "--op", "gt",
                     "--value", "0.5", "--batch", BATCH, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["totalElements"] > 0, f"Expected at least one run with {query}"
    print(f"✅ {query} --- {data['totalElements']} runs found")

# ------------------------------------------------------------
# v1 – Text equality search
# ------------------------------------------------------------
def test_text_equality(setup_data):
    query = "script = anchor_deviation_reset"
    result = run_cli("search", "--metric", "script", "--op", "eq",
                     "--value", "anchor_deviation_reset", "--batch", BATCH, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["totalElements"] > 0, f"Expected at least one run with {query}"
    print(f"✅ {query} --- {data['totalElements']} runs found")

# ------------------------------------------------------------
# v2 – Deeply nested dot‑path search
# ------------------------------------------------------------
def test_deep_path_search(setup_data):
    query = "parameters.device = cpu"
    result = run_cli("search", "--metric", "parameters.device", "--op", "eq",
                     "--value", "cpu", "--batch", BATCH, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["totalElements"] > 0, f"Expected runs with {query}"
    print(f"✅ {query} --- {data['totalElements']} runs found")

# ------------------------------------------------------------
# v3 – Full‑text phrase search
# ------------------------------------------------------------
def test_fulltext_search(setup_data):
    query = 'fulltext: "bert-base-uncased"'
    result = run_cli("search", "--q", "bert-base-uncased", "--batch", BATCH, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["totalElements"] > 0, f"Expected at least one run containing {query}"
    print(f"✅ {query} --- {data['totalElements']} runs found")

# ------------------------------------------------------------
# v3 – Fuzzy search
# ------------------------------------------------------------
def test_fuzzy_search(setup_data):
    query = 'fuzzy: "bert-base-uncasedd"'
    result = run_cli("search", "--q", "bert-base-uncasedd", "--fuzzy",
                     "--batch", BATCH, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["totalElements"] > 0, f"Fuzzy search should find runs with {query}"
    print(f"✅ {query} --- {data['totalElements']} runs found")

# ------------------------------------------------------------
# v3 – Summary output
# ------------------------------------------------------------
def test_summary_output(setup_data):
    query = "full_cacc > 0.9 (summary)"
    result = run_cli("search", "--metric", "full_cacc", "--op", "gt",
                     "--value", "0.9", "--batch", BATCH, "--summary")
    assert result.returncode == 0, result.stderr
    stdout = result.stdout
    assert ".json" in stdout, "Summary table should list source file names"
    print(f"✅ {query} --- summary table displayed")