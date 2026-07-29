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

    from cli import ledger
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

def test_show_blocks_prints_matched_pointers(mocker):
    """When a run has matched data, show_blocks prints Matches: lines."""
    run = {
        "id": 1,
        "payload": {"_source": {"file": "test.json"}, "experiment": "test_exp"},
        "matched": [
            {"pointer": "results[0].sst2_cacc", "value": 0.924312, "snippet": {"budget": 0.0}},
            {"pointer": "results[1].sst2_cacc", "value": 0.927752, "snippet": {"budget": 0.001}}
        ]
    }
    mock_print = mocker.patch("cli.ledger.console.print")
    from cli.ledger import show_blocks
    show_blocks(run, "results[].sst2_cacc", [1])
    # Some print calls may have no arguments (empty line) – filter them out
    printed_texts = [
        call.args[0] for call in mock_print.call_args_list if call.args
    ]
    assert any("Matches:" in str(t) for t in printed_texts)
    assert any("results[0].sst2_cacc" in str(t) for t in printed_texts)
    assert any("0.924312" in str(t) for t in printed_texts)
    assert any("results[1].sst2_cacc" in str(t) for t in printed_texts)
    assert any("0.927752" in str(t) for t in printed_texts)


def test_export_includes_pointer_fields():
    """build_export_blocks and format_blocks_as_text include pointer data."""
    from cli.ledger import build_export_blocks, format_blocks_as_text

    content = [
        {
            "id": 1,
            "payload": {"_source": {"file": "test.json"}, "experiment": "test_exp"},
            "matched": [
                {"pointer": "results[0].sst2_cacc", "value": 0.924312, "snippet": {"budget": 0.0}}
            ]
        }
    ]

    blocks = build_export_blocks(content, "results[].sst2_cacc", [0], include_pointers=True)
    assert len(blocks) == 1
    block = blocks[0]
    assert "pointer" in block
    assert block["pointer"] == "results[0].sst2_cacc"
    assert "value" in block
    assert block["value"] == 0.924312

    text = format_blocks_as_text(blocks)
    assert "match: results[0].sst2_cacc = 0.924312" in text

def test_diff_output(mocker):
    """Test that the diff command fetches two runs and prints a table."""
    mock_get = mocker.patch("requests.get")
    run1 = mocker.Mock()
    run1.status_code = 200
    run1.json.return_value = {
        "id": 1,
        "payload": {"accuracy": 0.95, "loss": 0.12, "experiment": "A"}
    }
    run2 = mocker.Mock()
    run2.status_code = 200
    run2.json.return_value = {
        "id": 2,
        "payload": {"accuracy": 0.92, "loss": 0.15, "experiment": "B"}
    }
    mock_get.side_effect = [run1, run2]

    mock_console = mocker.patch("cli.ledger.console.print")
    import argparse
    args = argparse.Namespace(id1=1, id2=2, json=False, history=False, batch=None)

    from cli.ledger import cli_diff
    cli_diff(args)

    # Assert that console.print was called at least once
    assert mock_console.call_count > 0
    # We can also check that a Table was printed by inspecting the first argument of some call
    table_printed = False
    for call in mock_console.call_args_list:
        if call.args and "Table" in str(type(call.args[0])):
            table_printed = True
            break
    assert table_printed, "Expected a Rich Table to be printed"

def test_search_save_and_load(mocker):
    """CLI --save and --saved send correct API payloads."""
    mock_post = mocker.patch("requests.post")
    mock_get = mocker.patch("requests.get")

    # --save path
    mock_post.return_value.status_code = 200

    import argparse
    save_args = argparse.Namespace(
        metrics=["accuracy"], ops=["gt"], values=["0.9"],
        combine=None, q=None, fuzzy=False, batch="sample",
        block=None, pointers=True, summary=False, json=False,
        save="my-query", saved=None
    )

    from cli.ledger import cli_search
    cli_search(save_args)

    # Verify POST was called with correct payload
    save_call = mock_post.call_args
    assert save_call[1]["json"]["name"] == "my-query"
    assert "accuracy" in save_call[1]["json"]["paramsJson"]

    # --saved path
    saved_response = mocker.Mock()
    saved_response.status_code = 200
    saved_response.json.return_value = {
        "name": "my-query",
        "batch": "sample",
        "paramsJson": '{"metrics":["accuracy"],"ops":["gt"],"values":["0.9"],"combine":"and","batch":"sample"}'
    }
    mock_get.return_value = saved_response

    # Also need to mock the actual search response (the one that fetches runs)
    search_response = mocker.Mock()
    search_response.status_code = 200
    search_response.json.return_value = {"content": [], "totalElements": 0}
    mock_get.side_effect = [saved_response, search_response]

    saved_args = argparse.Namespace(
        metrics=None, ops=None, values=None,
        combine=None, q=None, fuzzy=False, batch=None,
        block=None, pointers=True, summary=False, json=False,
        save=None, saved="my-query"
    )

    cli_search(saved_args)

    # Verify the search was performed with the saved parameters
    # (the second GET call should have metric=accuracy, op=gt, value=0.9)
    search_call = mock_get.call_args_list[1] if len(mock_get.call_args_list) > 1 else None
    assert search_call is not None
    assert "metric=accuracy" in search_call[0][0] or any("accuracy" in str(p) for p in search_call[1].values())

# ----------------------------------------------------------------------
# Aggregate test (NEW)
# ----------------------------------------------------------------------
def test_aggregate_output(mocker):
    """Test that the aggregate command calls the API and prints results."""
    mock_get = mocker.patch("requests.get")
    mock_response = mocker.Mock()
    mock_response.status_code = 200
    mock_response.json.return_value = [
        {"group": "0.001", "result": 0.95},
        {"group": "0.01",  "result": 0.88}
    ]
    mock_get.return_value = mock_response

    mock_print = mocker.patch("cli.ledger.console.print")
    import argparse
    args = argparse.Namespace(metric="accuracy", agg="AVG", group_by="learning_rate", batch="sample", json=False)

    from cli.ledger import cli_aggregate
    cli_aggregate(args)

    # Check that the table was printed (at least one call to console.print)
    assert mock_print.call_count > 0