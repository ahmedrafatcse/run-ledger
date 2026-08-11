# RunLedger REST API

Base URL: `http://localhost:8080/api`

## Runs

### Ingest a run

```http
POST /runs
```

**Body:** `{"payload": { ... }, "batch": "optional-batch-name"}`  
**Returns:** `201 Created` with the run object.

### Get a single run

```http
GET /runs/{id}
```

### Search / list runs

```http
GET /runs
```

**Parameters (all optional):**

| Param | Description |
| ------ | ----------- |
| `metric` | Dot-path to search (e.g., `metrics.accuracy`) |
| `op` | `gt`, `gte`, `lt`, `lte`, `eq` |
| `value` | Value to compare |
| `batch` | Batch name for isolation |
| `q` | Full-text / fuzzy phrase |
| `fuzzy` | `true` to enable fuzzy search |
| `block` | Block depth (0 = tightest match) |
| `pointers` | `true` (default) to include match locations |
| `page`, `size`, `sort` | Pagination |

### Compound search

```http
POST /runs/search
```

**Body:** `{"filters": [{"metric":"...","op":"...","value":"..."}, ...], "combine": "and|or", "batch": "..."}`

### Aggregate

```http
GET /runs/aggregate
```

**Parameters:**

| Param | Description |
| ------ | ----------- |
| `agg` | `AVG`, `MAX`, `MIN`, `SUM`, `COUNT` |
| `metric` | Field to aggregate (dot-path) |
| `groupBy` | Optional field to group by |
| `batch` | Optional batch filter |

### Metric discovery

```http
GET /runs/metrics
GET /runs/metrics?batch=...
GET /runs/metrics?batch=...&verbose=true
```

### Version history

```http
GET /runs/versions?batch=...&sourceFile=...&sourceIndex=...
```

## Saved Searches

### Save

```http
POST /saved
```

**Body:** `{"name": "...", "batch": "...", "paramsJson": "{...}"}`

### List

```http
GET /saved
GET /saved?batch=...
```

### Get

```http
GET /saved/{name}
GET /saved/{name}?batch=...
```

### Delete

```http
DELETE /saved/{name}
DELETE /saved/{name}?batch=...
```