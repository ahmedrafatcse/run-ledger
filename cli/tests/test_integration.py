import json
import subprocess
import tempfile
import time
import uuid
import os
from pathlib import Path

import pytest
import requests

PROJECT_ROOT = Path(__file__).parent.parent.parent
COMPOSE_FILE = PROJECT_ROOT / "docker-compose.yml"
HEALTH_URL = "http://localhost:8080/actuator/health"
API_BASE = "http://localhost:8080/api/runs"

def wait_for_health(timeout=120):
    start = time.time()
    while time.time() - start < timeout:
        try:
            resp = requests.get(HEALTH_URL, timeout=2)
            if resp.status_code == 200:
                return True
        except Exception:
            pass
        time.sleep(2)
    return False

@pytest.fixture(scope="session")
def docker_stack():
    """Start the Docker Compose stack and wait for it to be healthy."""
    subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "up", "-d", "--build"],
        cwd=str(PROJECT_ROOT),
        check=True,
        capture_output=True,
    )
    if not wait_for_health():
        subprocess.run(
            ["docker", "compose", "-f", str(COMPOSE_FILE), "down"],
            cwd=str(PROJECT_ROOT),
            capture_output=True,
        )
        pytest.fail("Backend did not become healthy in time")
    yield
    subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "down"],
        cwd=str(PROJECT_ROOT),
        capture_output=True,
    )

def run_cli(*args):
    cmd = ["python", str(PROJECT_ROOT / "cli" / "ledger.py"), *args]
    return subprocess.run(cmd, capture_output=True, encoding='utf-8', cwd=str(PROJECT_ROOT))

@pytest.fixture
def sample_folder():
    """Create a temp folder with valid and corrupt JSON files."""
    with tempfile.TemporaryDirectory() as tmpdir:
        folder = Path(tmpdir)
        (folder / "run1.json").write_text(json.dumps({
            "experiment": "exp1", "metrics": {"accuracy": 0.95, "loss": 0.1}
        }))
        (folder / "run2.json").write_text(json.dumps({
            "experiment": "exp2", "metrics": {"accuracy": 0.80, "loss": 0.2}
        }))
        (folder / "batch.json").write_text(json.dumps([
            {"experiment": "exp3", "metrics": {"f1": 0.88}},
            {"experiment": "exp4", "metrics": {"f1": 0.92}}
        ]))
        (folder / "bad.json").write_text("this is not json")
        yield folder

pytestmark = pytest.mark.integration

def test_full_golden_path(docker_stack, sample_folder):
    batch = f"golden-{uuid.uuid4()}"

    # 1. scan folder (non‑interactive)
    result = run_cli("scan", str(sample_folder), "--batch", batch)
    assert result.returncode == 0, result.stderr
    assert "Successfully ingested 4 run(s)" in result.stdout
    assert f"'{batch}'" in result.stdout

    # 2. metrics
    result = run_cli("metrics", "--batch", batch)
    assert result.returncode == 0, result.stderr
    metrics = json.loads(result.stdout)
    assert set(metrics).issuperset({"accuracy", "loss", "f1"})

    # 3. search: accuracy > 0.9
    result = run_cli("search", "--metric", "accuracy", "--op", "gt", "--value", "0.9", "--batch", batch, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["totalElements"] == 1
    run = data["content"][0]
    assert run["payload"]["experiment"] == "exp1"
    assert run["payload"]["metrics"]["accuracy"] == 0.95
    assert "_source" in run["payload"], "source metadata missing"
    assert run["payload"]["_source"]["file"] == "run1.json"

    # 4. search: loss < 0.15
    result = run_cli("search", "--metric", "loss", "--op", "lt", "--value", "0.15", "--batch", batch, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["totalElements"] == 1
    assert data["content"][0]["payload"]["experiment"] == "exp1"

    # 5. status
    result = run_cli("status")
    assert result.returncode == 0, result.stderr
    assert "running" in result.stdout.lower()

    # 6. deep API verification
    resp = requests.get(API_BASE, params={"batch": batch})
    assert resp.status_code == 200
    runs = resp.json()["content"]
    assert len(runs) == 4
    ids = [r["id"] for r in runs]
    assert len(set(ids)) == 4, "IDs must be unique"
    exp_names = {r["payload"]["experiment"] for r in runs}
    assert exp_names == {"exp1", "exp2", "exp3", "exp4"}
    for r in runs:
        assert r["payload"]["_source"]["file"] in ["run1.json", "run2.json", "batch.json"]
        assert r["createdAt"] is not None
    # Verify ordering (descending by createdAt)
    timestamps = [r["createdAt"] for r in runs]
    assert timestamps == sorted(timestamps, reverse=True), "runs must be ordered by createdAt desc"

def test_scan_nonexistent_folder(docker_stack):
    result = run_cli("scan", "/no/such/path")
    assert result.returncode != 0 or "Folder not found" in result.stdout

def test_scan_corrupt_file_skipped(docker_stack, sample_folder):
    batch = f"corrupt-{uuid.uuid4()}"
    result = run_cli("scan", str(sample_folder), "--batch", batch)
    assert result.returncode == 0, result.stderr
    assert "Successfully ingested 4 run(s)" in result.stdout

def test_metrics_empty_batch(docker_stack):
    result = run_cli("metrics", "--batch", f"nonexistent-{uuid.uuid4()}")
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == []

def test_search_no_results(docker_stack, sample_folder):
    batch = f"noresults-{uuid.uuid4()}"
    run_cli("scan", str(sample_folder), "--batch", batch)
    result = run_cli("search", "--metric", "accuracy", "--op", "gt", "--value", "0.99", "--batch", batch, "--json")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["totalElements"] == 0
    assert data["content"] == []

def test_multi_filter_and_returns_results():
    # Ensure the compound test data folder exists
    compound_dir = "test-data/sample-compound-runs"
    if not os.path.isdir(compound_dir):
        pytest.skip(f"Folder {compound_dir} not found")

    # Scan the folder with explicit UTF-8 encoding to avoid UnicodeDecodeError
    scan_result = subprocess.run(
        ["python", "cli/ledger.py", "scan", compound_dir, "--batch", "compound-int"],
        capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    assert scan_result.returncode == 0, f"Scan failed: {scan_result.stderr}"

    # Search with AND
    result = subprocess.run(
        ["python", "cli/ledger.py", "search", "--metric", "accuracy", "--op", "gt", "--value", "0.9",
         "--metric", "loss", "--op", "lt", "--value", "0.2", "--combine", "and", "--batch", "compound-int"],
        capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    assert result.returncode == 0, f"Search failed: {result.stderr}"
    assert result.stdout is not None, "stdout is None (decoding issue)"

    # compound_run1.json has accuracy=0.95, loss=0.15 → should match
    assert "compound_run1" in result.stdout, f"Output: {result.stdout}"