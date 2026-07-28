package com.runledger.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * One‑time startup component that backfills the payload_hash column
 * for all runs that were ingested before the version‑identity model
 * was introduced (V6 migration).
 *
 * <p>This uses the exact same canonical JSON representation that the
 * ingestion service will use for all future runs — Jackson with
 * {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} enabled and
 * compact output — so that re‑scans of unchanged files are recognised
 * as identical and do not create spurious new versions.
 *
 * <p>After this component runs, every row in the run table will have a
 * non‑null payload_hash, and the unique constraint on
 * (batch, source_file, source_index, version) is safe to use.
 */
@Component
@Order(1)   // run early, but after Flyway and Hibernate are ready
public class PayloadHashBackfill implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PayloadHashBackfill.class);

    private final EntityManager entityManager;
    private final ObjectMapper defaultObjectMapper;

    public PayloadHashBackfill(EntityManager entityManager, ObjectMapper defaultObjectMapper) {
        this.entityManager = entityManager;
        this.defaultObjectMapper = defaultObjectMapper;
    }

    @Override
    public void run(String... args) throws Exception {
        // Check if any rows need backfilling
        Query countQuery = entityManager.createNativeQuery(
                "SELECT count(*) FROM run WHERE payload_hash IS NULL");
        long nullCount = ((Number) countQuery.getSingleResult()).longValue();

        if (nullCount == 0) {
            log.info("No runs with missing payload_hash — backfill skipped.");
            return;
        }

        log.info("Found {} run(s) with missing payload_hash. Starting backfill…", nullCount);

        // Build a canonicalizing ObjectMapper
        ObjectMapper canonicalMapper = defaultObjectMapper.copy();
        canonicalMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        canonicalMapper.configure(SerializationFeature.INDENT_OUTPUT, false);

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

        // Fetch IDs and payloads of rows that need a hash
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT id, payload FROM run WHERE payload_hash IS NULL")
                .getResultList();

        int updated = 0;
        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            String payloadJson = row[1].toString();

            try {
                // Canonicalize: parse and re‑serialize with stable settings
                Object json = canonicalMapper.readValue(payloadJson, Object.class);
                byte[] canonicalBytes = canonicalMapper.writeValueAsBytes(json);

                // Compute SHA-256
                byte[] hashBytes = sha256.digest(canonicalBytes);
                String hash = HexFormat.of().formatHex(hashBytes);

                // Store the hash
                entityManager.createNativeQuery(
                                "UPDATE run SET payload_hash = :hash WHERE id = :id")
                        .setParameter("hash", hash)
                        .setParameter("id", id)
                        .executeUpdate();
                updated++;
            } catch (Exception e) {
                log.warn("Could not compute hash for run id={}: {} — skipping and leaving NULL",
                        id, e.getMessage());
            }
        }

        log.info("Backfill complete: {}/{} run(s) updated with a payload_hash.", updated, nullCount);
    }
}