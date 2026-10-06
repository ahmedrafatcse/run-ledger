"""
Test session configuration.

The CLI reads RUNLEDGER_BASE_URL at import time to compute API_BASE and
HEALTH_URL. Setting it here — before pytest imports any test module — makes
the tests deterministic regardless of the developer's shell environment.

Without this, running the tests in a shell where RUNLEDGER_BASE_URL is set
(e.g. pointing at a dev stack on :8081) causes tests that assert on
:8080 to fail for reasons unrelated to the code under test.
"""
import os

# Force the CLI module to compute against the default port when the test
# suite imports it. This must run before any test module is collected.
os.environ["RUNLEDGER_BASE_URL"] = "http://localhost:8080"

# RUNLEDGER_NO_DOCKER is honored by ensure_backend() but not by the CLI's
# top-level main() — leaving it set would produce different behavior on
# a developer's machine than in CI.
os.environ.pop("RUNLEDGER_NO_DOCKER", None)