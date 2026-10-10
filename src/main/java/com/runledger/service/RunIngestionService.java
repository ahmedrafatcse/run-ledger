package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.RunRequest;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.security.SecuredTransactionTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class RunIngestionService {

    private final RunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final SecuredTransactionTemplate secured;
    private final CanonicalJsonService canonicalJson;
    private final BatchSchemaService batchSchemaService;

    @PersistenceContext
    private EntityManager entityManager;

    public RunIngestionService(RunRepository runRepository,
                               ObjectMapper objectMapper,
                               SecuredTransactionTemplate secured,
                               CanonicalJsonService canonicalJson,
                               BatchSchemaService batchSchemaService) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.secured = secured;
        this.canonicalJson = canonicalJson;
        this.batchSchemaService = batchSchemaService;
    }

    /**
     * Ingests a run request inside a transaction scoped to the current
     * request's identity.
     *
     * <p><b>Ownership is derived from identity, never from the request.</b>
     * The {@code team_id} and {@code uploaded_by} columns are populated from
     * {@link AppSecurityContext}, not from anything in the submitted payload
     * or request. A client cannot declare which team owns a run.
     *
     * <p>Only users with the {@code researcher} role may submit runs.
     *
     * <p>After the run row is inserted, the batch's shorthand mapping is
     * updated with the new run's leaf paths, under an advisory lock scoped
     * to {@code (teamId, batch)}. This keeps the mapping fresh without a
     * full rebuild on every ingest.
     *
     * @param request the ingestion request
     * @return the saved Run entity (new or existing)
     * @throws IllegalStateException if the current identity is not a
     *         researcher with an assigned team
     */
    public Run ingest(RunRequest request) {
        AppSecurityContext.UserPrincipal p = AppSecurityContext.require();

        if (!"researcher".equals(p.role()) || p.teamId() == null) {
            throw new IllegalStateException(
                    "Only researchers with an assigned team can submit runs "
                            + "(current role: " + p.role() + ")");
        }

        return secured.execute(() -> {
            String payloadStr = request.payload().toString();
            String sourceFile = extractSourceFile(payloadStr);
            int sourceIndex = extractSourceIndex(payloadStr);

            if (sourceFile == null || sourceFile.isBlank()) {
                sourceFile = "anon-" + UUID.randomUUID().toString();
                sourceIndex = 0;
            }

            String batch = (request.batch() != null && !request.batch().isBlank())
                    ? request.batch() : null;
            String newHash = canonicalJson.hash(request.payload());

            Optional<Run> latestOpt = runRepository
                    .findTopByBatchAndSourceFileAndSourceIndexOrderByVersionDesc(
                            batch, sourceFile, sourceIndex);

            if (latestOpt.isPresent()) {
                Run latest = latestOpt.get();
                if (newHash.equals(latest.getPayloadHash())) {
                    return latest;
                }

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
            newVersion.setCanonVersion(CanonicalJsonService.CANON_VERSION);
            newVersion.setLatest(true);

            newVersion.setTeamId(p.teamId());
            newVersion.setUploadedBy(p.userId());

            Run saved = runRepository.save(newVersion);

            // Merge this run's leaf paths into the (team, batch) mapping.
            // Only runs with a batch participate; batchless runs aren't
            // addressable by shorthand and don't need a mapping entry.
            if (batch != null) {
                batchSchemaService.mergeIntoMapping(p.teamId(), batch, request.payload());
            }

            return saved;
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
}