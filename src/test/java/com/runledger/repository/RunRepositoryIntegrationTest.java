package com.runledger.repository;

import com.runledger.entity.Run;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)  // don't replace datasource with in‑memory
@Testcontainers
class RunRepositoryIntegrationTest {

    // Use the non‑deprecated DockerImageName constructor
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16"))
            .withDatabaseName("runledger")
            .withUsername("runledger")
            .withPassword("runledger");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private RunRepository runRepository;

    private Run runA;
    private Run runB;
    private Run runC;

    @BeforeEach
    void setUp() {
        runRepository.deleteAll();

        runA = new Run();
        runA.setPayload("{\"experiment\":\"A\",\"metrics\":{\"accuracy\":0.95,\"loss\":0.10}}");
        runA = runRepository.save(runA);

        runB = new Run();
        runB.setPayload("{\"experiment\":\"B\",\"metrics\":{\"accuracy\":0.80,\"loss\":0.25}}");
        runB = runRepository.save(runB);

        runC = new Run();
        runC.setPayload("{\"experiment\":\"C\",\"metrics\":{\"accuracy\":0.91,\"loss\":0.15,\"status\":\"completed\"}}");
        runC = runRepository.save(runC);
    }

    // ---------------------------------------------------------------
    // findByMetricGreaterThan
    // ---------------------------------------------------------------

    @Test
    void shouldFindRunsWithMetricGreaterThan() {
        Page<Run> result = runRepository.findByMetricGreaterThan("accuracy", 0.9, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactlyInAnyOrder(runA.getId(), runC.getId());   // 0.95, 0.91
    }

    @Test
    void greaterThan_noMatch_returnsEmpty() {
        Page<Run> result = runRepository.findByMetricGreaterThan("accuracy", 0.99, Pageable.unpaged());
        assertThat(result.getContent()).isEmpty();
    }

    // ---------------------------------------------------------------
    // findByMetricLessThan
    // ---------------------------------------------------------------

    @Test
    void shouldFindRunsWithMetricLessThan() {
        Page<Run> result = runRepository.findByMetricLessThan("loss", 0.20, Pageable.unpaged());
        // runA loss=0.10, runC loss=0.15 (runB loss=0.25 excluded)
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactlyInAnyOrder(runA.getId(), runC.getId());
    }

    // ---------------------------------------------------------------
    // findByMetricEquals (numeric)
    // ---------------------------------------------------------------

    @Test
    void shouldFindRunsWithMetricEqualToNumber() {
        Page<Run> result = runRepository.findByMetricEquals("accuracy", 0.91, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(runC.getId());
    }

    // ---------------------------------------------------------------
    // findByMetricEqualsText
    // ---------------------------------------------------------------

    @Test
    void shouldFindRunsWithTextMetricEquals() {
        Page<Run> result = runRepository.findByMetricEqualsText("status", "completed", Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(runC.getId());
    }

    @Test
    void textEquals_noMatch_returnsEmpty() {
        Page<Run> result = runRepository.findByMetricEqualsText("status", "running", Pageable.unpaged());
        assertThat(result.getContent()).isEmpty();
    }

    // ---------------------------------------------------------------
    // Edge case: metric key not present
    // ---------------------------------------------------------------

    @Test
    void metricNotPresent_returnsEmpty() {
        Page<Run> result = runRepository.findByMetricGreaterThan("nonexistent", 0.5, Pageable.unpaged());
        assertThat(result.getContent()).isEmpty();
    }
}