import json
import subprocess
from unittest.mock import MagicMock, patch, mock_open

import sys
import pytest
import requests
import argparse

# Import the module under test
from cli.ledger import (
    check_docker,
    backend_running,
    start_backend,
    stop_backend,
    ingest_folder,
    fetch_metrics,
    perform_search,
    display_results,
    interactive_search,
    PAGE_SIZE,
    API_BASE,
    HEALTH_URL,
)

# ----------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------
def fake_run_success(*args, **kwargs):
    """Simulate a successful subprocess call."""
    return MagicMock(returncode=0)

def fake_run_failure(*args, **kwargs):
    """Simulate a failed subprocess call."""
    raise subprocess.CalledProcessError(1, "mock")

# ----------------------------------------------------------------------
# Docker checks
# ----------------------------------------------------------------------
class TestCheckDocker:
    def test_docker_installed_and_running(self, mocker):
        mocker.patch("subprocess.run", return_value=MagicMock(returncode=0))
        assert check_docker() is True

    def test_docker_not_installed(self, mocker):
        # On Windows, a missing command returns a non-zero exit code, not an exception
        mocker.patch("subprocess.run", return_value=MagicMock(returncode=1, stderr="'docker' is not recognized"))
        assert check_docker() is False

    def test_docker_daemon_stopped(self, mocker):
        mocker.patch("subprocess.run", return_value=MagicMock(returncode=1))
        assert check_docker() is False

# ----------------------------------------------------------------------
# Backend health & lifecycle
# ----------------------------------------------------------------------
class TestBackendLifecycle:
    def test_backend_running(self, mocker):
        mock_get = mocker.patch("requests.get", return_value=MagicMock(status_code=200))
        assert backend_running() is True
        mock_get.assert_called_once_with(HEALTH_URL, timeout=2)

    def test_backend_not_running(self, mocker):
        mocker.patch("requests.get", side_effect=requests.ConnectionError)
        assert backend_running() is False

    def test_start_backend_creates_containers(self, mocker):
        mocker.patch("os.path.exists", return_value=True)
        mock_run = mocker.patch("subprocess.run")
        mocker.patch("cli.ledger.wait_for_backend", return_value=True)

        start_backend()
        mock_run.assert_called_once()  # docker compose up -d

    def test_stop_backend(self, mocker):
        mocker.patch("os.path.exists", return_value=True)
        mock_run = mocker.patch("subprocess.run")
        stop_backend()
        mock_run.assert_called_once()

# ----------------------------------------------------------------------
# Ingestion
# ----------------------------------------------------------------------
class TestIngestFolder:
    def test_ingest_single_json_creates_run(self, mocker):
        folder = "/fake/sweep"
        batch_name = "sweep"
        file_path = f"{folder}/run1.json"
        payload = {"accuracy": 0.95}

        # Mock filesystem
        mocker.patch("pathlib.Path.exists", return_value=True)
        mocker.patch("pathlib.Path.glob", return_value=[MagicMock(name=file_path)])
        mocker.patch("builtins.open", mock_open(read_data=json.dumps(payload)))

        # Mock HTTP POST
        mock_post = mocker.patch("requests.post", return_value=MagicMock(status_code=201))

        ingest_folder(folder, batch=batch_name)

        # Check that the POST was made with correct JSON
        call_args = mock_post.call_args
        sent_json = call_args[1]["json"]
        assert sent_json["batch"] == batch_name
        assert sent_json["payload"]["accuracy"] == 0.95

    def test_ingest_array_sends_each_element(self, mocker):
        folder = "/fake/sweep"
        data = [{"loss": 0.1}, {"loss": 0.2}]

        mocker.patch("pathlib.Path.exists", return_value=True)
        mocker.patch("pathlib.Path.glob", return_value=[MagicMock(name=f"{folder}/multi.json")])
        mocker.patch("builtins.open", mock_open(read_data=json.dumps(data)))
        mock_post = mocker.patch("requests.post", return_value=MagicMock(status_code=201))

        ingest_folder(folder)

        assert mock_post.call_count == 2   # one per element

    def test_ingest_skips_invalid_json(self, mocker):
        mocker.patch("pathlib.Path.exists", return_value=True)
        mocker.patch("pathlib.Path.glob", return_value=[MagicMock(name="bad.json")])
        mocker.patch("builtins.open", mock_open(read_data="not json"))
        mock_post = mocker.patch("requests.post")
        ingest_folder("/fake")
        mock_post.assert_not_called()

# ----------------------------------------------------------------------
# Metric discovery
# ----------------------------------------------------------------------
class TestFetchMetrics:
    def test_returns_metrics_list(self, mocker):
        mock_get = mocker.patch("requests.get", return_value=MagicMock(status_code=200,
                        json=MagicMock(return_value=["accuracy", "loss"])))
        metrics = fetch_metrics(batch="test")
        assert metrics == ["accuracy", "loss"]

    def test_handles_failure(self, mocker):
        mocker.patch("requests.get", return_value=MagicMock(status_code=500))
        metrics = fetch_metrics()
        assert metrics == []

# ----------------------------------------------------------------------
# Block search
# ----------------------------------------------------------------------
class TestPerformSearch:
    def test_search_returns_page(self, mocker):
        sample_page = {"content": [{"id": 1, "createdAt": "...", "payload": {}}],
                       "totalElements": 1, "totalPages": 1, "pageable": {"pageNumber": 0}}
        mock_get = mocker.patch("requests.get", return_value=MagicMock(status_code=200,
                        json=MagicMock(return_value=sample_page)))
        result = perform_search("acc", "gt", "0.9", batch="b1", page=0)
        assert result == sample_page

    def test_search_error_returns_none(self, mocker):
        mocker.patch("requests.get", side_effect=requests.ConnectionError)
        assert perform_search("acc", "gt", "0.9") is None

# ----------------------------------------------------------------------
# Display (just verify no exceptions)
# ----------------------------------------------------------------------
class TestDisplayResults:
    def test_displays_table_without_error(self, mocker):
        data = {"content": [{"id": 1, "createdAt": "2025-01-01", "payload": {"experiment": "test"}}],
                "totalElements": 1, "totalPages": 1, "pageable": {"pageNumber": 0}}
        # Mock Console to avoid real output
        mocker.patch("cli.ledger.console.print")
        display_results(data)  # should not raise

# ----------------------------------------------------------------------
# Interactive search (mock user input and API calls)
# ----------------------------------------------------------------------
class TestInteractiveSearch:
    def test_full_search_flow(self, mocker):
        # Mock metrics
        mocker.patch("cli.ledger.fetch_metrics", return_value=["accuracy", "loss"])
        # Mock search response
        mocker.patch("cli.ledger.perform_search", return_value={
            "content": [{"id": 1, "createdAt": "...", "payload": {}}],
            "totalPages": 1, "totalElements": 1, "pageable": {"pageNumber": 0}
        })
        # Mock console display
        mocker.patch("cli.ledger.console.print")
        # Mock prompts: select metric "1", operator ">", value "0.9", then quit
        prompt_patch = mocker.patch("cli.ledger.Prompt.ask",
                        side_effect=["1", ">", "0.9", "q", "n"])
        # Mock IntPrompt not used here
        interactive_search(batch="test")
        assert prompt_patch.call_count >= 4

def test_search_multi_filter_builds_correct_payload(mocker, monkeypatch):
    mock_post = mocker.patch("requests.post")
    mock_post.return_value.json.return_value = {"content": [], "totalElements": 0}
    mock_post.return_value.status_code = 200

    # Simulate CLI arguments as if typed on the command line
    test_args = [
        "ledger.py", "search",
        "--metric", "accuracy", "--op", "gt", "--value", "0.9",
        "--metric", "loss", "--op", "lt", "--value", "0.2",
        "--combine", "and",
        "--batch", "test-batch"
    ]
    monkeypatch.setattr("sys.argv", test_args)

    import ledger
    ledger.main()

    expected_url = "http://localhost:8080/api/runs/search"
    expected_payload = {
        "filters": [
            {"metric": "accuracy", "op": "gt", "value": "0.9"},
            {"metric": "loss", "op": "lt", "value": "0.2"}
        ],
        "combine": "and",
        "batch": "test-batch"
    }
    mock_post.assert_any_call(expected_url, json=expected_payload)
# ----------------------------------------------------------------------
# Run the tests with: pytest cli/tests/