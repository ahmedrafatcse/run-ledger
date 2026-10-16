# API Reference

All endpoints require an `X-User-Id` header containing a valid user UUID.
Role and team are looked up server-side; the client cannot declare them.

## Runs

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/runs` | List runs visible to the caller. Params: `batch`, `q`, `fuzzy`, `metric`, `op`, `value`, `block`, `pointers`, `page`, `size` |
| `POST` | `/api/runs` | Ingest a run. Body: `{ "payload": {...}, "batch": "..." }` |
| `GET` | `/api/runs/{id}` | Fetch one run |
| `GET` | `/api/runs/versions` | Versions of a run identity. Params: `batch`, `sourceFile`, `sourceIndex` |
| `POST` | `/api/runs/search` | Compound AND/OR search |
| `GET` | `/api/runs/aggregate` | `AVG` / `MAX` / `MIN` / `SUM` / `COUNT`, optional group-by |
| `GET` | `/api/runs/metrics` | Metric keys for a batch |

## Batches

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/batches` | Batches visible to the caller, grouped by team |

## Saved searches

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/saved` | Save a search |
| `GET` | `/api/saved` | List the caller's saved searches |
| `GET` | `/api/saved/{name}` | Fetch one by name |
| `DELETE` | `/api/saved/{name}` | Delete one |

## Health

| Method | Path |
|---|---|
| `GET` | `/actuator/health` |