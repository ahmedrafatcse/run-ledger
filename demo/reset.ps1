<#
.SYNOPSIS
    Reset the RunLedger demo environment to a known-good state.

.DESCRIPTION
    Truncates the run table, re-seeds teams and users, POSTs sample runs
    through the API as Alice and Bob, then verifies the resulting state
    matches what the demo expects.

    Runs are posted through the API rather than inserted with SQL so that
    payload_hash is computed by the real ingestion path. Demo data with
    real hashes means the integrity badge on the detail page is a real
    check, not a placeholder.

    One of Alice's runs (alice-4.json) has a nested payload: a config
    object with an optimizer.settings.learning_rate field, and an array
    of per-epoch results. That run exercises nested-path and
    array-element queries in the demo.

    The owner credentials used here (POSTGRES_USER=runledger) are demo
    setup, not part of the application's security boundary. The app
    itself runs as runledger_app and cannot write to teams or app_users
    under RLS. That separation is what makes the tamper demo meaningful.

    Idempotent: running twice leaves the same state.

.PARAMETER BaseUrl
    Base URL of the running app. Default http://localhost:8081 (dev stack).

.PARAMETER SkipTruncate
    Skip the truncate step. Useful if you want to add runs without wiping.
#>
param(
    [string]$BaseUrl = "http://localhost:8081",
    [switch]$SkipTruncate
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$composeFiles = @(
    "-f", "$root\docker-compose.yml",
    "-f", "$root\docker-compose.dev.yml"
)

$aliceId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
$bobId   = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
$teamA   = "11111111-1111-1111-1111-111111111111"
$teamB   = "22222222-2222-2222-2222-222222222222"

# ── 1. Locate the Postgres container ────────────────────────────────
Write-Host "Locating Postgres container..." -ForegroundColor Cyan
$pg = (docker compose @composeFiles ps -q postgres | Select-Object -First 1)
if (-not $pg) {
    Write-Error "Postgres container not found. Run 'docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d' first."
    exit 1
}

# ── 2. Confirm the app is reachable ─────────────────────────────────
Write-Host "Checking app health at $BaseUrl..." -ForegroundColor Cyan
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 3
    if ($health.status -ne "UP") { throw "app not UP" }
} catch {
    Write-Error "App not reachable at $BaseUrl. Start the dev stack first."
    exit 1
}

# ── 3. Truncate run table ───────────────────────────────────────────
if (-not $SkipTruncate) {
    Write-Host "Truncating run table..." -ForegroundColor Cyan
    docker exec -i $pg psql -U runledger -d runledger -c "TRUNCATE TABLE run RESTART IDENTITY;" | Out-Null
}

# ── 4. Seed teams, users, assignments ───────────────────────────────
Write-Host "Seeding teams and users..." -ForegroundColor Cyan
Get-Content "$root\demo\seed.sql" -Raw | docker exec -i $pg psql -U runledger -d runledger | Out-Null

# ── 5. Post sample runs via the API ─────────────────────────────────

# Flat payload: a run with top-level metric fields.
function Post-Run {
    param(
        [string]$UserId,
        [string]$Batch,
        [string]$SourceFile,
        [double]$Accuracy,
        [double]$Loss,
        [string]$Notes = "demo run"
    )
    $payload = @{
        payload = @{
            experiment = "demo"
            accuracy   = $Accuracy
            loss       = $Loss
            notes      = $Notes
            _source    = @{ file = $SourceFile; index = 0 }
        }
        batch = $Batch
    } | ConvertTo-Json -Depth 10 -Compress

    Invoke-RestMethod -Uri "$BaseUrl/api/runs" -Method Post `
        -Headers @{ "X-User-Id" = $UserId } `
        -ContentType "application/json" `
        -Body $payload | Out-Null

    Write-Host ("  posted {0,-20} as {1}" -f $SourceFile, $Batch)
}

# Nested payload: a run shaped like a real training sweep with a config
# object and an array of per-epoch results. Exercises nested-path and
# array-element queries in the demo.
function Post-NestedRun {
    param(
        [string]$UserId,
        [string]$Batch,
        [string]$SourceFile
    )
    $payload = @{
        payload = @{
            experiment = "demo-nested"
            config = @{
                optimizer = @{
                    name     = "AdamW"
                    settings = @{
                        learning_rate = 0.0001
                        weight_decay  = 0.01
                    }
                }
            }
            results = @(
                @{ epoch = 1; accuracy = 0.91; loss = 0.18 }
                @{ epoch = 2; accuracy = 0.94; loss = 0.12 }
                @{ epoch = 3; accuracy = 0.95; loss = 0.10 }
            )
            _source = @{ file = $SourceFile; index = 0 }
        }
        batch = $Batch
    } | ConvertTo-Json -Depth 10 -Compress

    Invoke-RestMethod -Uri "$BaseUrl/api/runs" -Method Post `
        -Headers @{ "X-User-Id" = $UserId } `
        -ContentType "application/json" `
        -Body $payload | Out-Null

    Write-Host ("  posted {0,-20} as {1}" -f $SourceFile, $Batch)
}

Write-Host "Posting sample runs as Alice..." -ForegroundColor Cyan
Post-Run       -UserId $aliceId -Batch "demo-alice" -SourceFile "alice-1.json" -Accuracy 0.95 -Loss 0.12 -Notes "baseline run"
Post-Run       -UserId $aliceId -Batch "demo-alice" -SourceFile "alice-2.json" -Accuracy 0.91 -Loss 0.15 -Notes "tuned optimizer"
Post-Run       -UserId $aliceId -Batch "demo-alice" -SourceFile "alice-3.json" -Accuracy 0.88 -Loss 0.20 -Notes "ablated layer 6"
Post-NestedRun -UserId $aliceId -Batch "demo-alice" -SourceFile "alice-4.json"

Write-Host "Posting sample runs as Bob..." -ForegroundColor Cyan
Post-Run -UserId $bobId -Batch "demo-bob" -SourceFile "bob-1.json" -Accuracy 0.93 -Loss 0.14 -Notes "bobs baseline"
Post-Run -UserId $bobId -Batch "demo-bob" -SourceFile "bob-2.json" -Accuracy 0.89 -Loss 0.18 -Notes "bobs ablation"

# ── 6. Verify expected state ────────────────────────────────────────
Write-Host "Verifying demo state..." -ForegroundColor Cyan

$countsRaw = docker exec -i $pg psql -U runledger -d runledger -t -A `
    -c "SELECT team_id, COUNT(*) FROM run GROUP BY team_id;"

$teamACount = 0
$teamBCount = 0
$countsRaw | ForEach-Object {
    if ($_ -match "^(?<team>[0-9a-f-]+)\|(?<count>\d+)$") {
        switch ($Matches.team) {
            $teamA { $teamACount = [int]$Matches.count }
            $teamB { $teamBCount = [int]$Matches.count }
        }
    }
}

if ($teamACount -ne 4) {
    Write-Error "Expected 4 runs for Team A, got $teamACount"
    exit 1
}
if ($teamBCount -ne 2) {
    Write-Error "Expected 2 runs for Team B, got $teamBCount"
    exit 1
}

$nullHashes = docker exec -i $pg psql -U runledger -d runledger -t -A `
    -c "SELECT COUNT(*) FROM run WHERE payload_hash IS NULL OR payload_hash = '';"
$nullHashes = [int]$nullHashes.Trim()
if ($nullHashes -ne 0) {
    Write-Error "Expected 0 null hashes, got $nullHashes"
    exit 1
}

Write-Host ""
Write-Host "Reset complete. Verified:" -ForegroundColor Green
Write-Host ("  Team A: {0} runs" -f $teamACount)
Write-Host ("  Team B: {0} runs" -f $teamBCount)
Write-Host "  All rows have non-null payload hashes"
Write-Host ""
Write-Host "Next:"
Write-Host "  Open $BaseUrl and log in as Alice, Bob, or Sup."
Write-Host "  To run the tamper demo: Get-Content demo\tamper.sql -Raw | docker exec -i $pg psql -U runledger -d runledger"
Write-Host "  To reset again: .\demo\reset.ps1"
Write-Host ""