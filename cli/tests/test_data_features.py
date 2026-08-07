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
import tempfile

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
    return subprocess.run(cmd, capture_output=True, encoding='utf-8', cwd=str(PROJECT_ROOT))

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
    assert "Successfully ingested 10 run(s)" in result.stdout

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
                     "--value", "0.8", "--batch", BATCH_NAME, "--json")
    assert result.returncode == 0
    data = json.loads(result.stdout)
    assert data["totalElements"] == 2   # run4, run5
    print(f"✅ metrics.accuracy > 0.8 --- {data['totalElements']} runs found")

def test_v1_text_equality_search_root_key(setup_data):
    """Text equality search on the top-level 'status' field."""
    # run5.json has "status": "completed" at the root
    result = run_cli("search", "--metric", "status", "--op", "eq",
                     "--value", "completed", "--batch", BATCH_NAME, "--json")
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
                     "--op", "eq", "--value", "0.0001", "--batch", BATCH_NAME, "--json")
    assert result.returncode == 0
    data = json.loads(result.stdout)
    # run5 has learning_rate = 0.0001
    assert data["totalElements"] == 1
    print("✅ config.optimizer.settings.learning_rate = 0.0001 --- 1 run found")

def test_v2_shorthand_key_resolution_shallowest_wins(setup_data):
    """Shorthand 'accuracy' resolves to the shallowest occurrence (root in run3.json)."""
    result = run_cli("search", "--metric", "accuracy", "--op", "gt",
                     "--value", "0.8", "--batch", BATCH_NAME, "--json")
    assert result.returncode == 0
    data = json.loads(result.stdout)
    # Only run3 has a root-level accuracy (0.88)
    assert data["totalElements"] == 1
    assert data["content"][0]["payload"]["experiment"] == "simple_top_level"
    print("✅ Shorthand 'accuracy' (shallowest wins) > 0.8 --- 1 run found")

def test_v2_batch_isolation_empty(setup_data):
    """Searching a different batch returns no results."""
    result = run_cli("search", "--metric", "accuracy", "--op", "gt",
                     "--value", "0.5", "--batch", "nonexistent-batch", "--json")
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
    result = run_cli("search", "--q", "calibration", "--batch", BATCH_NAME, "--json")
    assert result.returncode == 0
    data = json.loads(result.stdout)
    # Both run1 and run2 mention "calibration"
    assert data["totalElements"] >= 1, "Expected at least one run with 'calibration'"
    print(f"✅ Full‑text: 'calibration' --- {data['totalElements']} runs found")

def test_v3_fuzzy_search(setup_data):
    """Fuzzy search with a typo still finds the original word."""
    # "calibraation" is a misspelling of "calibration"
    result = run_cli("search", "--q", "calibraation", "--fuzzy",
                     "--batch", BATCH_NAME, "--json")
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
    assert "duplicate_keys" in stdout or "deep_config" in stdout, "Summary should list at least one source file"
    print("✅ metrics.accuracy > 0.9 (summary) --- table displayed")


# ------------------------------------------------------------
# v4 – Array‑aware search (new)
# ------------------------------------------------------------
def test_array_basic_search(setup_data):
    """Search inside an array: client.results[].threshold > 0.96"""
    result = run_cli("search", "--metric", "client.results[].threshold",
                     "--op", "gt", "--value", "0.96",
                     "--batch", BATCH_NAME, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    # run7 has 0.99, run8 has 0.99 (plus non‑numeric values safely ignored)
    assert data["totalElements"] == 2
    print("✅ Array basic search (threshold > 0.96) --- 2 runs found")

def test_array_text_search(setup_data):
    """Search inside an array: client.results[].status = 'error'"""
    result = run_cli("search", "--metric", "client.results[].status",
                     "--op", "eq", "--value", "error",
                     "--batch", BATCH_NAME, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    # Only run8 has status = "error"
    assert data["totalElements"] == 1
    print("✅ Array text search (status = 'error') --- 1 run found")

def test_array_stress_nested_search(setup_data):
    """Search inside nested arrays: phases[].calibration.points[].threshold > 0.97"""
    result = run_cli("search", "--metric", "phases[].calibration.points[].threshold",
                     "--op", "gt", "--value", "0.97",
                     "--batch", BATCH_NAME, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    # run9 has 0.99 (phase1) and 0.98 (phase2) – both > 0.97
    assert data["totalElements"] == 1
    print("✅ Array stress nested search (threshold > 0.97) --- 1 run found")

def test_array_mixed_types_safe(setup_data):
    """Search inside an array with mixed types: client.results[].threshold > 0.7"""
    result = run_cli("search", "--metric", "client.results[].threshold",
                     "--op", "gt", "--value", "0.7",
                     "--batch", BATCH_NAME, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    # run7 (0.99,0.95) and run8 (0.99,'none',0.5) – non‑numeric safely ignored
    assert data["totalElements"] == 2
    print("✅ Array mixed‑type safety (threshold > 0.7) --- 2 runs found")

# ------------------------------------------------------------
# JSONL ingestion
# ------------------------------------------------------------
def test_jsonl_ingestion(setup_data):
    """Ingest a JSONL file line by line and verify correct _source metadata."""
    batch = f"jsonl-test-{uuid.uuid4().hex[:8]}"

    with tempfile.TemporaryDirectory() as tmpdir:
        # Create a JSONL file with 3 records
        jl_file = Path(tmpdir) / "steps.jsonl"
        lines = [
            '{"step": 0, "loss": 0.95}',
            '{"step": 1, "loss": 0.82}',
            '{"step": 2, "loss": 0.67}',
        ]
        jl_file.write_text("\n".join(lines), encoding="utf-8")

        # Scan with --jsonl
        result = run_cli("scan", str(tmpdir), "--batch", batch, "--jsonl")
        assert result.returncode == 0, f"scan failed: {result.stderr}"

        # Verify via API
        resp = requests.get(API_BASE, params={"batch": batch, "size": 50})
        assert resp.status_code == 200, f"API error: {resp.text}"
        data = resp.json()
        content = data.get("content", [])
        assert len(content) == 3, f"Expected 3 runs, got {len(content)}"

        # Check _source metadata
        for run in content:
            payload = run.get("payload", {})
            src = payload.get("_source", {})
            assert src.get("file") == "steps.jsonl", f"Unexpected file: {src}"
            idx = src.get("index")
            assert idx is not None and 0 <= idx <= 2, f"Index out of range: {idx}"
            # Verify the actual data lines up
            expected = json.loads(lines[idx])
            for k, v in expected.items():
                assert payload.get(k) == v, f"Mismatch at line {idx}: {k} {v} != {payload.get(k)}"

        # Clean up the temporary batch
        requests.delete(f"{API_BASE}/batch/{batch}")  # if this endpoint exists, otherwise manual cleanup

def test_nested_array_block_depth():
    """Verify block‑depth on nested arrays using direct API calls."""
    import uuid
    import requests

    batch = f"nest-{uuid.uuid4().hex[:6]}"

    # 1. Ingest the nested file directly via the API
    payload = {
        "experiment": "nested_arrays_block_test",
        "clients": [
            {
                "client_name": "clean_client",
                "sweep": [
                    {"w_base": 0.5, "fin_asr": 0.12, "sst2_asr": 0.07},
                    {"w_base": 0.75, "fin_asr": 0.09, "sst2_asr": 0.06}
                ]
            },
            {
                "client_name": "poisoned_client",
                "sweep": [
                    {"w_base": 0.5, "fin_asr": 0.45, "sst2_asr": 0.88},
                    {"w_base": 1.0, "fin_asr": 0.28, "sst2_asr": 0.72}
                ]
            }
        ]
    }
    resp = requests.post(API_BASE, json={"payload": payload, "batch": batch})
    assert resp.status_code == 201, f"ingest failed: {resp.text}"

    # 2. Search for fin_asr < 0.3 with block=0
    params = {"metric": "clients[].sweep[].fin_asr", "op": "lt", "value": "0.3",
              "batch": batch, "block": "0", "size": "1", "pointers": "true"}
    resp = requests.get(API_BASE, params=params)
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert data["totalElements"] == 1
    run = data["content"][0]

    # 3. Verify matched pointers exist
    matched = run.get("matched")
    assert matched is not None, "matched pointers missing"
    assert len(matched) == 3, f"expected 3 matches, got {len(matched)}"

    # 4. block=1 should return the sweep array (a list)
    params["block"] = "1"
    resp = requests.get(API_BASE, params=params)
    assert resp.status_code == 200, resp.text
    data1 = resp.json()
    block1_payload = data1["content"][0]["payload"]
    assert isinstance(block1_payload, list), f"block=1 payload should be a list, got {type(block1_payload)}"
    assert any("fin_asr" in json.dumps(elem) for elem in block1_payload)

    # 5. block=2 should return the client object
    params["block"] = "2"
    resp = requests.get(API_BASE, params=params)
    assert resp.status_code == 200, resp.text
    data2 = resp.json()
    block2_payload = data2["content"][0]["payload"]
    assert isinstance(block2_payload, dict) and block2_payload.get("client_name") == "clean_client"

    # 6. block=3 should return the clients array
    params["block"] = "3"
    resp = requests.get(API_BASE, params=params)
    assert resp.status_code == 200, resp.text
    data3 = resp.json()
    block3_payload = data3["content"][0]["payload"]
    assert isinstance(block3_payload, list) and len(block3_payload) > 0