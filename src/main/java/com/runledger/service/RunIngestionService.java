package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.runledger.dto.RunRequest;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import com.runledger.security.AppSecurityContext;
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
     * <p><b>Ownership is derived from identity, never from the request.</b>
     * The {@code team_id} and {@code uploaded_by} columns are populated from
     * {@link AppSecurityContext}, not from anything in the submitted payload
     * or request. A client cannot declare which team owns a run; only the
     * server-resolved identity can. If a {@code teamId} field is ever added
     * to {@link RunRequest}, it must be ignored here - honouring it would
     * allow a researcher to submit a run that appears to belong to another
     * team.
     *
     * <p>Only users with the {@code researcher} role may submit runs.
     * Supervisors observe; they do not submit. Admins are excluded for the
     * same reason until an explicit admin-submission path is defined.
     *
     * <p>The transaction wrapper sets the Postgres role and the
     * {@code app.current_user_id} session variable so that Slice 6's RLS
     * policies can filter rows by team.
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
            String newHash = computeCanonicalHash(request.payload());

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
            newVersion.setLatest(true);

            newVersion.setTeamId(p.teamId());
            newVersion.setUploadedBy(p.userId());

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