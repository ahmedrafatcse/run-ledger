# Stack Overview (Spring Boot Project)

This project is a backend system built using a Java + Spring Boot ecosystem with a relational database and containerized local development setup.

---

## ☕ Java
Java is the core programming language used to implement all backend logic.

It is used for:
- Controllers (API endpoints)
- Services (business logic)
- Entities (database models)
- DTOs (data transfer objects)

**MERN equivalent:** JavaScript (Node.js runtime)

---

## 🌱 Spring Boot
Spring Boot is the backend framework built on top of Java.

It provides:
- REST API development (`@RestController`)
- Dependency Injection (managing classes automatically)
- Auto-configuration (reduces boilerplate setup)
- Embedded server (runs the application)

**MERN equivalent:** Express.js

---

## 📦 Maven
Maven is the build and dependency management tool for Java projects.

It is responsible for:
- Downloading project dependencies
- Compiling source code
- Running tests
- Packaging the application into a `.jar`
- Managing build lifecycle

Configuration is defined in `pom.xml`.

**MERN equivalent:** npm / yarn + package.json

---

## 🗄️ PostgreSQL
PostgreSQL is the relational database used in this project.

It stores structured data in:
- Tables
- Rows
- Columns

The application interacts with it via Spring Data JPA.

**MERN equivalent:** MongoDB

---

## 🧱 Flyway
Flyway is a database migration tool.

It ensures database schema changes are:
- Version controlled
- Reproducible across environments
- Automatically applied on startup

Example migrations:
- Create tables
- Add columns
- Modify schema safely over time

**MERN equivalent:** No standard built-in equivalent (sometimes Prisma or custom scripts)

---

## 🐳 Docker (docker-compose)
Docker is used to run PostgreSQL in a containerized environment.

`docker-compose.yml`:
- Starts PostgreSQL automatically
- Configures database credentials
- Exposes ports
- Ensures isolated local development

This avoids installing PostgreSQL manually on your machine.

**MERN equivalent:** Optional Docker setup for MongoDB or services

---

## 🧠 High-Level Architecture

```text
Java Code
   ↓
Spring Boot (API layer)
   ↓
Spring Data JPA (ORM layer)
   ↓
PostgreSQL (Database)

Maven → builds project
Flyway → manages schema changes
Docker → runs database locally
```

---

## ⚖️ Mental Model

- Spring Boot handles **backend logic and APIs**
- Maven handles **building and dependency management**
- Flyway handles **database evolution**
- Docker handles **local infrastructure**
- PostgreSQL handles **data storage**

Together, they form a production-grade backend setup similar in spirit to a MERN stack, but more structured and enterprise-oriented.
```