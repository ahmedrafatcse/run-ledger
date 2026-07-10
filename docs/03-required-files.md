# Required Files (Project Overview)

This section explains the purpose of key configuration and infrastructure files in the project.

---

## 1. `docker-compose.yml`

The `docker-compose.yml` file defines the local development environment. It configures and runs a PostgreSQL 16 database container with environment variables, exposed ports, and persistent storage.

Running `docker compose up` starts a fully configured PostgreSQL instance without requiring PostgreSQL to be installed locally.

### Key responsibilities:
- Starts PostgreSQL in a Docker container
- Provides an isolated database per developer machine
- Ensures consistent database setup across environments

---

## 2. `pom.xml`

The `pom.xml` (Project Object Model) file is Maven’s build configuration file. It defines project metadata, Java version, Spring Boot version, dependencies, and plugins.

Maven uses it to:
- Download dependencies
- Compile source code
- Run tests
- Package the application into a `.jar`

### Key responsibilities:
- Dependency management (Spring Web, JPA, PostgreSQL, Flyway, etc.)
- Build lifecycle management
- Project configuration

**Analogy:** Similar to `requirements.txt`, but also controls build and packaging.

---

## 3. `.gitattributes`

Controls how Git handles files in the repository.

### Key responsibilities:
- Normalizes line endings across OS (Windows/Linux/Mac)
- Defines diff behavior for specific file types
- Ensures consistent Git behavior across contributors

---

## 4. `.gitignore`

Specifies files and folders that Git should ignore.

### Common ignores:
- `target/` (build output)
- `.idea/` (IntelliJ settings)
- `.env` (secrets)
- Logs and temporary files

---

## 5. `mvnw`

Maven Wrapper script for Linux/macOS.

### Key responsibilities:
- Runs Maven without requiring global installation
- Ensures consistent Maven version across machines

Example:
```bash
./mvnw spring-boot:run
```

---

## 6. `mvnw.cmd`

Windows version of the Maven Wrapper.

### Key responsibilities:
- Same as `mvnw`
- Used in Command Prompt / PowerShell

Example:
```cmd
mvnw.cmd spring-boot:run
```

---

## 7. `.idea/`

IntelliJ IDEA configuration folder.

### Key responsibilities:
- Stores IDE settings (run configs, UI layout, code style)
- Personal to each developer
- Not part of the application

---

## 8. `.mvn/`

Contains Maven Wrapper configuration.

### Key responsibilities:
- Ensures consistent Maven version across environments
- Defines wrapper download behavior

---

## 9. `target/`

Maven build output directory.

### Key responsibilities:
- Stores compiled `.class` files
- Contains packaged `.jar` file
- Holds test reports and build artifacts

### Important:
- Automatically generated
- Should NOT be committed to Git
- Regenerated on every build (`mvn clean package`)
```