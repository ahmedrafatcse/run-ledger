"""
Feature showcase tests using test-data/sample-runs.
Each test is labelled with a version number (v1, v2, v3) to reflect
the order in which features were added.

Run with:
    python -m pytest cli/tests/test_data_features.py -v -s

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
API_BASE = "http://localhost:8080/api/runs"
SAMPLE_FOLDER = PROJECT_ROOT / "test-data" / "sample-runs"
# Unique batch per session to avoid cross‑test interference
BATCH_NAME = f"feature-test-{uuid.uuid4().hex[:8]}"

# ------------------------------------------------------------
# Helper: run the CLI script
# ------------------------------------------------------------
def run_cli(*args):
    cmd = ["python", str(PROJECT_ROOT / "cli" / "ledger.py"), *args]
    return subprocess.run(cmd, capture_output=True, text=True, cwd=str(PROJECT_ROOT))

# ------------------------------------------------------------
# Ensure backend is available, clean batch, and ingest sample data
# ------------------------------------------------------------
@pytest.fixture(scope="session")
def setup_data():
    """Wait for backend, clear any old mapping for this batch, then scan."""
    # Wait for backend health
    for _ in range(30):
        try:
            resp = requests.get(HEALTH_URL, timeout=2)
            if resp.status_code == 200:
                break
        except Exception:
            pass
        time.sleep(2)
    else:
        pytest.fail("Backend is not reachable. Start it with 'docker compose up -d'")

    # Ingest sample runs into the fresh batch
    result = run_cli("scan", str(SAMPLE_FOLDER), "--batch", BATCH_NAME)
    assert result.returncode == 0, result.stderr
    assert "Successfully ingested 5 run(s)" in result.stdout

# ------------------------------------------------------------
# v1 – Basic metric block search
# ------------------------------------------------------------
def test_v1_metric_discovery(setup_data):
    """Metrics endpoint returns keys from the batch (via mapping)."""
    result = run_cli("metrics", "--batch", BATCH_NAME)
    assert result.returncode == 0
    keys = json.loads(result.stdout)
    # Keys present in the sample files
    assert "accuracy" in keys
    assert "loss" in keys
    assert "f1_score" in keys
    assert "experiment" in keys
    print("✅ Metric discovery (batch) --- SUCCESS")

def test_v1_numeric_block_search_dot_path(setup_data):
    """Numeric greater-than search using explicit dot-path metrics.accuracy."""
    # Only run4 and run5 have a metrics.accuracy field (0.91 and 0.93 > 0.8)
    result = run_cli("search", "--metric", "metrics.accuracy", "--op", "gt",
                     "--value", "0.8", "--batch", BATCH_NAME)
    assert result.returncode == 0
    data = json.loads(result.stdout)
    assert data["totalElements"] == 2   # run4, run5
    print(f"✅ metrics.accuracy > 0.8 --- {data['totalElements']} runs found")

def test_v1_text_equality_search_root_key(setup_data):
    """Text equality search on the top-level 'status' field."""
    # run5.json has "status": "completed" at the root
    result = run_cli("search", "--metric", "status", "--op", "eq",
                     "--value", "completed", "--batch", BATCH_NAME)
    assert result.returncode == 0
    data = json.loads(result.stdout)
    assert data["totalElements"] == 1
    assert data["content"][0]["payload"]["experiment"] == "deep_config"
    print("✅ status = completed (root key) --- 1 run found")

# ------------------------------------------------------------
# v2 – Generalised path, shorthand keys, batch isolation
# ------------------------------------------------------------
def test_v2_dot_path_search(setup_data):
    """Explicit dot‑path works for deeply nested fields."""
    result = run_cli("search", "--metric", "config.optimizer.settings.learning_rate",
                     "--op", "eq", "--value", "0.0001", "--batch", BATCH_NAME)
    assert result.returncode == 0
    data = json.loads(result.stdout)
    # run5 has learning_rate = 0.0001
    assert data["totalElements"] == 1
    print("✅ config.optimizer.settings.learning_rate = 0.0001 --- 1 run found")

def test_v2_shorthand_key_resolution_shallowest_wins(setup_data):
    """Shorthand 'accuracy' resolves to the shallowest occurrence (root in run3.json)."""
    result = run_cli("search", "--metric", "accuracy", "--op", "gt",
                     "--value", "0.8", "--batch", BATCH_NAME)
    assert result.returncode == 0
    data = json.loads(result.stdout)
    # Only run3 has a root-level accuracy (0.88)
    assert data["totalElements"] == 1
    assert data["content"][0]["payload"]["experiment"] == "simple_top_level"
    print("✅ Shorthand 'accuracy' (shallowest wins) > 0.8 --- 1 run found")

def test_v2_batch_isolation_empty(setup_data):
    """Searching a different batch returns no results."""
    result = run_cli("search", "--metric", "accuracy", "--op", "gt",
                     "--value", "0.5", "--batch", "nonexistent-batch")
    assert result.returncode == 0
    data = json.loads(result.stdout)
    assert data["totalElements"] == 0
    print("✅ accuracy > 0.5 in non‑existent batch --- 0 runs (correct)")

# ------------------------------------------------------------
# v3 – Full‑text phrase search, fuzzy search, summary output
# ------------------------------------------------------------
def test_v3_fulltext_phrase_search(setup_data):
    """Full‑text search finds a phrase inside notes (using stemming)."""
    # run1 notes contain "calibration samples"
    result = run_cli("search", "--q", "calibration", "--batch", BATCH_NAME)
    assert result.returncode == 0
    data = json.loads(result.stdout)
    # Both run1 and run2 mention "calibration"
    assert data["totalElements"] >= 1, "Expected at least one run with 'calibration'"
    print(f"✅ Full‑text: 'calibration' --- {data['totalElements']} runs found")

def test_v3_fuzzy_search(setup_data):
    """Fuzzy search with a typo still finds the original word."""
    # "calibraation" is a misspelling of "calibration"
    result = run_cli("search", "--q", "calibraation", "--fuzzy",
                     "--batch", BATCH_NAME)
    assert result.returncode == 0
    data = json.loads(result.stdout)
    assert data["totalElements"] >= 1, "Fuzzy search should find runs with 'calibration'"
    print(f"✅ Fuzzy: 'calibraation' --- {data['totalElements']} runs found")

def test_v3_summary_output(setup_data):
    """Summary flag returns only IDs and source files."""
    # Use dot-path to metrics.accuracy to get results
    result = run_cli("search", "--metric", "metrics.accuracy", "--op", "gt",
                     "--value", "0.9", "--batch", BATCH_NAME, "--summary")
    assert result.returncode == 0
    stdout = result.stdout
    # Should find run4 and run5
    assert "run4.json" in stdout or "run5.json" in stdout, "Summary should list at least one source file"
    assert "|" in stdout, "Summary table should be displayed"
    print("✅ metrics.accuracy > 0.9 (summary) --- table displayed")