import json
import subprocess
import tempfile
import time
import uuid
import os
from pathlib import Path

import pytest
import requests

# ------------------------------------------------------------
# Global settings – will be overridden dynamically by the fixture
# ------------------------------------------------------------
PROJECT_ROOT = Path(__file__).parent.parent.parent
COMPOSE_FILE = PROJECT_ROOT / "docker-compose.test.yml"

# Prevent CLI from ever auto‑starting Docker
os.environ["RUNLEDGER_NO_DOCKER"] = "1"

# These will be set to the actual random host port once the stack is up
HEALTH_URL = None
API_BASE = None


def get_host_port(project, service="app", container_port="8080"):
    """
    Return the host port that Docker assigned to *container_port* for the
    given service inside the Compose project.
    """
    cmd = [
        "docker", "compose", "-f", str(COMPOSE_FILE), "-p", project,
        "port", service, container_port
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=str(PROJECT_ROOT))
    # Example output: "0.0.0.0:54321" or "127.0.0.1:12345"
    return result.stdout.strip().split(":")[-1]


@pytest.fixture(scope="session")
def docker_stack():
    """Start a completely isolated test stack with random host ports."""
    project = f"rl-test-{uuid.uuid4().hex[:8]}"
    compose_cmd = [
        "docker", "compose", "-f", str(COMPOSE_FILE),
        "-p", project,
        "up", "-d", "--build"
    ]
    subprocess.run(compose_cmd, cwd=str(PROJECT_ROOT), check=True, capture_output=True)

    # Dynamically discover the random port Docker assigned to the app
    host_port = get_host_port(project)
    health_url = f"http://localhost:{host_port}/actuator/health"
    api_base = f"http://localhost:{host_port}/api/runs"

    # Update global variables so all tests use the correct address
    globals()["HEALTH_URL"] = health_url
    globals()["API_BASE"] = api_base

    # Make CLI commands (run_cli) use the same port
    os.environ["RUNLEDGER_BASE_URL"] = f"http://localhost:{host_port}"
    os.environ["RUNLEDGER_PORT"] = host_port

    # Wait for the backend to become healthy
    for _ in range(60):
        try:
            if requests.get(health_url, timeout=2).status_code == 200:
                break
        except Exception:
            time.sleep(2)
    else:
        pytest.fail("Backend did not become healthy")

    # Seed dev users so the identity filter accepts CLI requests.
    SEED_SQL = """
    INSERT INTO teams (id, name) VALUES
      ('11111111-1111-1111-1111-111111111111', 'Team A'),
      ('22222222-2222-2222-2222-222222222222', 'Team B')
    ON CONFLICT (id) DO NOTHING;
    INSERT INTO app_users (id, email, display_name, app_role, team_id) VALUES
      ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'alice@example.com', 'Alice', 'researcher', '11111111-1111-1111-1111-111111111111'),
      ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob@example.com',   'Bob',   'researcher', '22222222-2222-2222-2222-222222222222'),
      ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'sup@example.com',   'Sup',   'supervisor', NULL)
    ON CONFLICT (id) DO NOTHING;
    INSERT INTO supervisor_team_assignments (supervisor_id, team_id) VALUES
      ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111'),
      ('cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222')
    ON CONFLICT DO NOTHING;
    """

    pg_container = subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "-p", project,
         "ps", "-q", "postgres"],
        capture_output=True, text=True, cwd=str(PROJECT_ROOT)
    ).stdout.strip()

    subprocess.run(
        ["docker", "exec", "-i", pg_container,
         "psql", "-U", "runledger", "-d", "runledger", "-c", SEED_SQL],
        check=True, cwd=str(PROJECT_ROOT)
    )

    yield api_base

    # Tear everything down
    subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "-p", project, "down", "-v"],
        cwd=str(PROJECT_ROOT), capture_output=True
    )
    # Restore env so subsequent test files don't inherit the dead port.
    os.environ.pop("RUNLEDGER_BASE_URL", None)
    os.environ.pop("RUNLEDGER_PORT", None)

def run_cli(*args):
    """Run the CLI in a subprocess, inheriting the environment (port, no-docker)."""
    cmd = ["python", str(PROJECT_ROOT / "cli" / "ledger.py"), *args]
    return subprocess.run(
        cmd,
        capture_output=True,
        encoding='utf-8',
        cwd=str(PROJECT_ROOT)
    )


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

    # 1. scan folder
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

    # 6. deep API verification (uses dynamic API_BASE)
    resp = requests.get(API_BASE, params={"batch": batch},
                        headers={"X-User-Id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"})
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
    compound_dir = "test-data/sample-compound-runs"
    if not os.path.isdir(compound_dir):
        pytest.skip(f"Folder {compound_dir} not found")

    scan_result = subprocess.run(
        ["python", "cli/ledger.py", "scan", compound_dir, "--batch", "compound-int"],
        capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    assert scan_result.returncode == 0, f"Scan failed: {scan_result.stderr}"

    result = subprocess.run(
        ["python", "cli/ledger.py", "search", "--metric", "accuracy", "--op", "gt", "--value", "0.9",
         "--metric", "loss", "--op", "lt", "--value", "0.2", "--combine", "and", "--batch", "compound-int"],
        capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    assert result.returncode == 0, f"Search failed: {result.stderr}"
    assert result.stdout is not None, "stdout is None (decoding issue)"
    assert "compound_run1" in result.stdout, f"Output: {result.stdout}"