# RunLedger

A self‑hosted, SQL‑queryable experiment run registry.  
Ingest any JSON artifact, search by metric, and (soon) verify tamper‑evidence.

## Quick start

1. **Start the database**  
   ```bash
   docker compose up -d
   ```

2. **Run the application**
   ```bash
   ./mvnw spring-boot:run
   ```

3. **Ingest a run**
   ```bash
   curl -X POST http://localhost:8080/api/runs \
     -H "Content-Type: application/json" \
     -d '{"payload":{"experiment":"test","metrics":{"accuracy":0.95}}}'
   ```

4. **Search runs**
   ```bash
   curl "http://localhost:8080/api/runs?metric=accuracy&op=gt&value=0.90"
   ```

## API Reference

Full documentation: [docs/api.md](docs/api.md)

### Quick endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/runs` | Ingest a single run (JSON with a `payload` field) |
| `GET` | `/api/runs/{id}` | Retrieve a single run by ID |
| `GET` | `/api/runs?metric=...&op=...&value=...` | Block search (metric filtering) |

### Operators

| Operator | Meaning |
|----------|---------|
| `gt` | Greater than |
| `gte` | Greater than or equal to |
| `lt` | Less than |
| `lte` | Less than or equal to |
| `eq` | Equal to (numeric or text) |

### Example requests

#### Ingest a run

```bash
curl -X POST http://localhost:8080/api/runs \
  -H "Content-Type: application/json" \
  -d '{"payload":{"experiment":"WAG_vs_SLERP","config":{"lr":0.001},"metrics":{"accuracy":0.94,"asr":0.02}}}'
```

#### Get run by ID

```bash
curl http://localhost:8080/api/runs/1
```

#### Find runs with accuracy > 0.9

```bash
curl "http://localhost:8080/api/runs?metric=accuracy&op=gt&value=0.9"
```

#### Find runs with loss <= 0.15

```bash
curl "http://localhost:8080/api/runs?metric=loss&op=lte&value=0.15"
```

#### Text equality (e.g., status = "completed")

```bash
curl "http://localhost:8080/api/runs?metric=status&op=eq&value=completed"
```

## Observability

RunLedger includes Actuator endpoints for health checks and metrics.

- **Health** (includes database connectivity): `GET /actuator/health`
- **Application info**: `GET /actuator/info`
- **Metrics**: `GET /actuator/metrics`

## Tech stack

- **Language:** Java 17
- **Framework:** Spring Boot 4.0
- **Database:** PostgreSQL 16 (JSONB)
- **Migrations:** Flyway
- **Testing:** JUnit 5, Mockito, Testcontainers
- **Build:** Maven
- **Containerisation:** Docker, Docker Compose

## Project structure

```
run-ledger/
├── src/main/java/com/runledger/
│   ├── controller/          # REST endpoints
│   ├── dto/                 # Request / response records
│   ├── entity/              # JPA entities
│   ├── repository/          # Spring Data JPA repositories
│   ├── service/             # Business logic
│   ├── config/              # Configuration (security, logging)
│   ├── security/            # Authentication/authorisation (future)
│   ├── exception/           # Global exception handler
│   └── util/                # Helper utilities
├── src/main/resources/
│   ├── application.yml      # Main configuration
│   └── db/migration/        # Flyway SQL scripts
├── src/test/                # Unit & integration tests
├── docker-compose.yml       # PostgreSQL container
├── pom.xml                  # Maven build
└── README.md
```

## Why RunLedger?

Existing experiment trackers either require SDK lock‑in (MLflow, Aim) or are generic databases that leave schema design and indexing up to the user (Datasette, ClickHouse). RunLedger fills the gap:

- **Zero SDK lock‑in** – works with any language or tool that can write JSON to disk or `curl`.
- **SQL‑powered querying** – safe parameterised API for everyday searches, with a planned sandboxed raw SQL endpoint for advanced users.
- **Opinionated JSONB schema** – avoids sparse‑column pollution; no migrations needed when metrics change.
- **Self‑hosted & lightweight** – one `docker compose up` command to start the database, then run the Spring Boot app.
- **Tamper‑evidence (coming soon)** – cryptographic hash chain to prove runs haven’t been altered after ingestion.

## Development

### Prerequisites

- Java 17+
- Docker (for PostgreSQL)
- Maven (or use the included `mvnw` wrapper)

### Run tests

```bash
./mvnw test
```

### Build JAR

```bash
./mvnw clean package
```

### Run with Docker (app + database)

```bash
docker compose up -d
./mvnw spring-boot:run
```

## Roadmap

- [x] Block search (parameterised metric filtering)
- [x] Health & metrics endpoints
- [x] Robust error handling
- [ ] Pagination & sorting
- [ ] Full‑text & fuzzy search (already have DB indexes)
- [ ] Sandboxed raw SQL endpoint
- [ ] Tamper‑evidence (hash chain + integrity verification)
- [ ] Batch ingestion (zip upload)
- [ ] Swagger UI (when compatible with Spring Boot 4)

## License

MIT (or your preferred license)
```