package com.runledger.repository;

import com.runledger.entity.Run;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class RunRepositoryIntegrationTest {

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
        Page<Run> result = runRepository.findByMetricGreaterThan(
                "metrics.accuracy", 0.9, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactlyInAnyOrder(runA.getId(), runC.getId());
    }

    @Test
    void greaterThan_noMatch_returnsEmpty() {
        Page<Run> result = runRepository.findByMetricGreaterThan(
                "metrics.accuracy", 0.99, Pageable.unpaged());
        assertThat(result.getContent()).isEmpty();
    }

    // ---------------------------------------------------------------
    // findByMetricLessThan
    // ---------------------------------------------------------------

    @Test
    void shouldFindRunsWithMetricLessThan() {
        Page<Run> result = runRepository.findByMetricLessThan(
                "metrics.loss", 0.20, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactlyInAnyOrder(runA.getId(), runC.getId());
    }

    // ---------------------------------------------------------------
    // findByMetricEquals (numeric)
    // ---------------------------------------------------------------

    @Test
    void shouldFindRunsWithMetricEqualToNumber() {
        Page<Run> result = runRepository.findByMetricEquals(
                "metrics.accuracy", 0.91, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(runC.getId());
    }

    // ---------------------------------------------------------------
    // findByMetricEqualsText
    // ---------------------------------------------------------------

    @Test
    void shouldFindRunsWithTextMetricEquals() {
        Page<Run> result = runRepository.findByMetricEqualsText(
                "metrics.status", "completed", Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(runC.getId());
    }

    @Test
    void textEquals_noMatch_returnsEmpty() {
        Page<Run> result = runRepository.findByMetricEqualsText(
                "metrics.status", "running", Pageable.unpaged());
        assertThat(result.getContent()).isEmpty();
    }

    // ---------------------------------------------------------------
    // Edge case: metric key not present
    // ---------------------------------------------------------------

    @Test
    void metricNotPresent_returnsEmpty() {
        Page<Run> result = runRepository.findByMetricGreaterThan(
                "metrics.nonexistent", 0.5, Pageable.unpaged());
        assertThat(result.getContent()).isEmpty();
    }

    // ---------------------------------------------------------------
    // Deeply nested path
    // ---------------------------------------------------------------

    @Test
    void shouldQueryDeeplyNestedPath() {
        Run deepRun = new Run();
        deepRun.setPayload("{\"config\":{\"optimizer\":{\"settings\":{\"learning_rate\":0.0001}}}}");
        runRepository.save(deepRun);

        Page<Run> result = runRepository.findByMetricGreaterThan(
                "config.optimizer.settings.learning_rate", 0.00001, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(deepRun.getId());

        Page<Run> emptyResult = runRepository.findByMetricGreaterThan(
                "config.optimizer.settings.learning_rate", 0.1, Pageable.unpaged());
        assertThat(emptyResult.getContent()).isEmpty();
    }

    // ---------------------------------------------------------------
    // Full‑text phrase search
    // ---------------------------------------------------------------

    @Test
    void shouldFindRunsByPhrase() {
        Run phraseRun = new Run();
        phraseRun.setPayload("{\"experiment\":\"test\",\"notes\":\"applied weighted averaging to merge\"}");
        runRepository.save(phraseRun);

        // exact phrase should match (stemming handles "weighted" → "weight")
        Page<Run> result = runRepository.searchByPhrase("weighted averaging", Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(phraseRun.getId());

        // a different phrase should return nothing
        Page<Run> emptyResult = runRepository.searchByPhrase("random phrase", Pageable.unpaged());
        assertThat(emptyResult.getContent()).isEmpty();
    }

    // ---------------------------------------------------------------
    // Fuzzy trigram search
    // ---------------------------------------------------------------

    @Test
    void shouldFindRunsByFuzzyMatch() {
        Run fuzzyRun = new Run();
        fuzzyRun.setPayload("{\"experiment\":\"test\",\"notes\":\"applied weighted averaging\"}");
        runRepository.save(fuzzyRun);

        // close spelling should still match via trigram similarity
        Page<Run> result = runRepository.searchByFuzzy("weighted avaraging", 0.3, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(fuzzyRun.getId());

        // a completely unrelated term should return nothing
        Page<Run> emptyResult = runRepository.searchByFuzzy("random", 0.3, Pageable.unpaged());
        assertThat(emptyResult.getContent()).isEmpty();
    }
}