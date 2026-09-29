package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces a canonical SHA-256 hex digest of a JSON payload.
 *
 * <p>Canonical means: keys sorted at every nesting level, no indentation,
 * no incidental whitespace. Two payloads that represent the same logical
 * content produce the same hash regardless of key order or formatting.
 *
 * <p>This service is the single source of truth for payload hashing. Both
 * the ingestion path (which has the payload as a parsed JsonNode) and the
 * display path (which reads the stored payload back as a String) must use
 * it. Two independent canonicalization implementations would drift, and
 * drift would produce false integrity failures on the UI - which is
 * exactly the failure mode this class exists to prevent.
 */
@Service
public class CanonicalJsonService {

    private final ObjectMapper objectMapper;

    public CanonicalJsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Hash a payload that is already a parsed JsonNode. Used by ingestion,
     * where the request body has been deserialized into a tree.
     */
    public String hash(JsonNode payload) {
        try {
            // Convert to a plain Java object graph (Map, List, primitives)
            // so that ORDER_MAP_ENTRIES_BY_KEYS applies at every nesting
            // level. Serializing an ObjectNode directly does not reliably
            // sort keys, which would make the hash order-sensitive and
            // produce different hashes for equivalent content.
            Object plain = objectMapper.treeToValue(payload, Object.class);
            byte[] bytes = canonicalMapper().writeValueAsBytes(plain);
            return sha256Hex(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash payload", e);
        }
    }

    /**
     * Hash a payload stored as a JSON string. Used by the integrity check,
     * which reads the payload column back as text.
     *
     * <p>Parses the string first so the canonical bytes are identical to
     * what {@link #hash(JsonNode)} produces for the same logical content.
     * This matters because Postgres jsonb reformats payloads on write
     * (strips whitespace, reorders keys, normalizes number formatting) -
     * a raw string hash would never match the ingestion-time hash.
     */
    public String hash(String payloadJson) {
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            return hash(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash payload", e);
        }
    }

    private ObjectMapper canonicalMapper() {
        ObjectMapper canonical = objectMapper.copy();
        canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        canonical.configure(SerializationFeature.INDENT_OUTPUT, false);
        return canonical;
    }

    private String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(sha256.digest(bytes));
    }
}