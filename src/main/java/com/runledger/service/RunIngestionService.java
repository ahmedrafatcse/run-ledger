package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.runledger.dto.RunRequest;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import com.runledger.security.SecuredTransactionTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class RunIngestionService {

    private final RunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final SecuredTransactionTemplate secured;

    @PersistenceContext
    private EntityManager entityManager;

    public RunIngestionService(RunRepository runRepository,
                               ObjectMapper objectMapper,
                               SecuredTransactionTemplate secured) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.secured = secured;
    }

    /**
     * Ingests a run request inside a transaction scoped to the current
     * request's identity.
     *
     * <p>The transaction wrapper sets the Postgres role and the
     * {@code app.current_user_id} session variable so that Slice 6's RLS
     * policies can filter rows by team. When RLS is disabled (today),
     * the wrapper is a no-op for behavior but still enforces the
     * identity-bound transaction boundary.
     *
     * @param request the ingestion request
     * @return the saved Run entity (new or existing)
     */
    public Run ingest(RunRequest request) {
        return secured.execute(() -> {
            String payloadStr = request.payload().toString();
            String sourceFile = extractSourceFile(payloadStr);
            int sourceIndex = extractSourceIndex(payloadStr);

            // If no _source was provided, assign a random identity to avoid collisions
            if (sourceFile == null || sourceFile.isBlank()) {
                sourceFile = "anon-" + UUID.randomUUID().toString();
                sourceIndex = 0;
            }

            String batch = (request.batch() != null && !request.batch().isBlank())
                    ? request.batch() : null;
            String newHash = computeCanonicalHash(request.payload());

            Optional<Run> latestOpt = runRepository
                    .findTopByBatchAndSourceFileAndSourceIndexOrderByVersionDesc(
                            batch, sourceFile, sourceIndex);

            if (latestOpt.isPresent()) {
                Run latest = latestOpt.get();
                if (newHash.equals(latest.getPayloadHash())) {
                    return latest;
                }

                // Mark previous version as not latest.
                // Native UPDATE touches only the `latest` column - the column
                // grant permits this and rejects any attempt to modify content
                // columns. Entity save() here would emit a full-row UPDATE.
                entityManager.createNativeQuery(
                                "UPDATE run SET latest = false WHERE id = :id")
                        .setParameter("id", latest.getId())
                        .executeUpdate();
            }

            Run newVersion = new Run();
            newVersion.setPayload(payloadStr);
            newVersion.setBatch(batch);
            newVersion.setSourceFile(sourceFile);
            newVersion.setSourceIndex(sourceIndex);
            newVersion.setVersion(latestOpt.map(r -> r.getVersion() + 1).orElse(1));
            newVersion.setPayloadHash(newHash);
            newVersion.setLatest(true);
            return runRepository.save(newVersion);
        });
    }

    // ── Private helpers ──

    private String extractSourceFile(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            JsonNode source = root.path("_source").path("file");
            return source.isTextual() ? source.asText() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int extractSourceIndex(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            JsonNode index = root.path("_source").path("index");
            return index.isInt() ? index.asInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String computeCanonicalHash(Object payload) {
        try {
            ObjectMapper canonical = objectMapper.copy();
            canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            canonical.configure(SerializationFeature.INDENT_OUTPUT, false);

            byte[] canonicalBytes = canonical.writeValueAsBytes(payload);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(canonicalBytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute payload hash", e);
        }
    }
}