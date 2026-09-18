package com.runledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the column-level grants from Slice 3.
 *
 * <p>The application role ({@code runledger_app}) has only SELECT, INSERT, and
 * UPDATE (latest) on the {@code run} table. These tests confirm that content
 * columns cannot be modified and that rows cannot be deleted, while the
 * {@code latest} flag remains writable.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
@Transactional
class RunImmutabilityTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("runledger")
            .withUsername("runledger")
            .withPassword("runledger")
            .withInitScript("initdb/01-create-app-role.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "runledger_app");
        registry.add("spring.datasource.password", () -> "runledger");
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void updatePayload_isRejected() {
        Long id = insertTestRun();

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE run SET payload = ?::jsonb WHERE id = ?",
                        "{\"tampered\": true}", id))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void updatePayloadHash_isRejected() {
        Long id = insertTestRun();

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE run SET payload_hash = ? WHERE id = ?",
                        "deadbeef", id))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void deleteRun_isRejected() {
        Long id = insertTestRun();

        assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM run WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void updateLatest_isPermitted() {
        Long id = insertTestRun();

        int rows = jdbcTemplate.update(
                "UPDATE run SET latest = false WHERE id = ?", id);
        assertThat(rows).isEqualTo(1);

        Boolean latest = jdbcTemplate.queryForObject(
                "SELECT latest FROM run WHERE id = ?", Boolean.class, id);
        assertThat(latest).isFalse();
    }

    @Test
    void insertNewRun_isPermitted() {
        Long id = insertTestRun();
        assertThat(id).isNotNull().isPositive();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM run WHERE id = ?", Integer.class, id);
        assertThat(count).isEqualTo(1);
    }

    private Long insertTestRun() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO run (payload, source_file, source_index, version, latest)
                VALUES (?::jsonb, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "{\"test\": true}",
                "immutability-test.json",
                0,
                1,
                true);
    }
}