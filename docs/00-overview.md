# RunLedger

**A tamper-evident, SQL-queryable registry for experiment runs.**

---

## Problem

Researchers and small ML teams produce hundreds of structured experiment artifacts — `run.json` files, metric logs, configuration dumps. These artifacts are usually scattered across folders and version-controlled ad-hoc. Two real pains emerge:

1. **Queryability** – “Show me all runs where ASR < 0.1 and balanced_utility > 0.9, from the last six months.” Without a proper store, answering this means writing brittle Python scripts over flat files.
2. **Trust** – When a paper is submitted, reviewers and readers must trust that the reported numbers match the actual experiment outputs, and that those outputs haven’t been altered after the fact.

Existing tools often solve one of these problems, but not both in a single, lightweight, language-agnostic service.

---

## Solution

RunLedger is a **self-hosted, Spring Boot-based registry** that:

- Ingests arbitrary JSON experiment artifacts via a REST API — no changes to your training code.
- Stores them in a **PostgreSQL `jsonb`** column, making every field queryable with raw SQL.
- Maintains a **cryptographic hash chain** across runs, so any tampering with stored results is immediately detectable.
- Provides a simple API that returns clean JSON and supports both individual and batch queries.

It is designed to sit *alongside* your existing experiment pipeline — not inside it.

---

## Key Features

### 📊 SQL-Queryable Runs
- Query experiment history using PostgreSQL’s full power (metrics, configs, tags, dates, JSON fields).
- Planned support for full-text search and fuzzy matching.

---

### 🔒 Tamper-Evident Integrity
- Each run receives a SHA-256 hash at ingestion.
- Hashes are linked in a chain per experiment.
- `/integrity` endpoint verifies the chain for modifications.
- Future: optional Ed25519 signatures for cryptographic verification.

---

### 🧩 Language-Agnostic & Unobtrusive
- No SDK required.
- Any system that can send JSON over HTTP can use it.
- Works for ML experiments, benchmarks, A/B tests, etc.

---

### 🛡️ Secure by Default
- JWT-based authentication
- Role-based access control
- API-key support for CI/CD usage

---

### 📦 Production-Ready Foundations
- Flyway migrations for database schema
- Docker + docker-compose for local setup
- Testcontainers for integration tests
- Spring Boot Actuator for health/metrics

---

## Architecture

RunLedger follows a layered architecture:

| Layer | Responsibility |
|------|----------------|
| Controller | REST endpoints (thin request handling layer) |
| Service | Business logic (hashing, ingestion, integrity checks) |
| Repository | Database access via JPA |
| Entity | Database models |
| DTO | API request/response contracts |
| Security | Authentication & authorization |
| Exception | Global error handling |

---

## Design Decisions

### JSONB as core storage
All runs are stored as raw JSON in PostgreSQL `jsonb`, enabling flexible schema evolution without migrations for every new metric.

### Hash chain over complexity
A linear hash chain provides lightweight tamper detection for sequential runs.

### Constructor injection
Ensures testability and explicit dependencies.

### Flyway migrations
All schema changes are versioned and reproducible.

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Java 17 |
| Framework | Spring Boot 4.0 |
| Build Tool | Maven |
| Database | PostgreSQL 16 (`jsonb`) |
| Migrations | Flyway |
| Security | Spring Security, JWT (planned) |
| Testing | JUnit 5, MockMvc, Testcontainers |
| Infra | Docker, Docker Compose |

---

## Repository Structure (Planned)

```text
run-ledger/
├── src/main/java/com/runledger/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   ├── dto/
│   ├── config/
│   ├── security/
│   ├── exception/
│   └── util/
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
├── src/test/
├── docker-compose.yml
├── Dockerfile
├── pom.xml
└── README.md
```

---

## Roadmap

| Phase | Features |
|------|----------|
| 1 | Core ingestion, retrieval, Flyway, Docker setup |
| 2 | Hash chain + integrity verification endpoint |
| 3 | Full-text + fuzzy search (Postgres extensions) |
| 4 | JWT auth + roles + API keys |
| 5 | Experiment grouping layer |
| 6 | Advanced cryptographic integrity (Merkle tree, signatures) |
| 7 | Observability + minimal dashboard |

---

## Getting Started (Placeholder)

```bash
git clone https://github.com/your-username/run-ledger.git
cd run-ledger

docker compose up -d

./mvnw spring-boot:run
```

Example request:

```bash
curl -X POST http://localhost:8080/api/runs \
  -H "Content-Type: application/json" \
  -d '{"experiment":"WAG_vs_SLERP","metrics":{"accuracy":0.94,"asr":0.02}}'
```

---

## Why RunLedger Exists

RunLedger was born from building research benchmark frameworks where experiment outputs became scattered JSON files. Over time, two needs became clear:

- A system to **query experiments easily**
- A system to **guarantee they weren’t modified**

Existing tools solved these separately, but not together in a lightweight backend service.

RunLedger combines both into a single system: queryable, verifiable, and reproducible.