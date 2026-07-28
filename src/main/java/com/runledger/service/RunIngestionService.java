package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.runledger.dto.RunRequest;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class RunIngestionService {

    private final RunRepository runRepository;
    private final ObjectMapper objectMapper;

    public RunIngestionService(RunRepository runRepository, ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Ingests a run request, applying versioning based on content hash.
     *
     * <p>Identity key: (batch, _source.file, _source.index).
     * If a run with the same identity already exists and the canonical payload
     * hash matches the latest version, no insert occurs and the existing run
     * is returned (idempotent). If the hash differs, a new version is inserted.
     *
     * @param request the ingestion request containing payload and optional batch
     * @return the saved Run entity (new or existing)
     */
    @Transactional
    public Run ingest(RunRequest request) {
        // 1. Extract identity from payload
        String payloadStr = request.payload().toString();
        String sourceFile = extractSourceFile(payloadStr);
        int sourceIndex = extractSourceIndex(payloadStr);
        String batch = (request.batch() != null && !request.batch().isBlank()) ? request.batch() : null;

        // 2. Compute canonical hash of the new payload
        String newHash = computeCanonicalHash(request.payload());

        // 3. Look up the latest version for this identity
        Optional<Run> latestOpt = runRepository
                .findTopByBatchAndSourceFileAndSourceIndexOrderByVersionDesc(batch, sourceFile, sourceIndex);

        if (latestOpt.isPresent()) {
            Run latest = latestOpt.get();
            // If the payload hasn't changed, return existing (idempotent)
            if (newHash.equals(latest.getPayloadHash())) {
                return latest;
            }
            // Otherwise, create a new version
            Run newVersion = new Run();
            newVersion.setPayload(payloadStr);
            newVersion.setBatch(batch);
            newVersion.setSourceFile(sourceFile);
            newVersion.setSourceIndex(sourceIndex);
            newVersion.setVersion(latest.getVersion() + 1);
            newVersion.setPayloadHash(newHash);
            return runRepository.save(newVersion);
        } else {
            // First version
            Run firstVersion = new Run();
            firstVersion.setPayload(payloadStr);
            firstVersion.setBatch(batch);
            firstVersion.setSourceFile(sourceFile);
            firstVersion.setSourceIndex(sourceIndex);
            firstVersion.setVersion(1);
            firstVersion.setPayloadHash(newHash);
            return runRepository.save(firstVersion);
        }
    }

    // ── Private helpers ──

    /**
     * Extracts the _source.file string from the JSON payload.
     * Defaults to "unknown" if not present.
     */
    private String extractSourceFile(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            JsonNode source = root.path("_source").path("file");
            return source.isTextual() ? source.asText() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Extracts the _source.index integer from the JSON payload.
     * Defaults to 0 if not present or not a number.
     */
    private int extractSourceIndex(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            JsonNode index = root.path("_source").path("index");
            return index.isInt() ? index.asInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Computes a SHA‑256 hex digest of the canonical JSON representation.
     *
     * <p>Canonical means object keys are sorted alphabetically and no
     * indentation is applied, so formatting differences don't affect the hash.
     */
    private String computeCanonicalHash(Object payload) {
        try {
            // Build a canonicalizing mapper (sorted keys, compact output)
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