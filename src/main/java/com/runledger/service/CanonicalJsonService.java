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
 * drift would produce false integrity failures on the UI.
 *
 * <p><b>Canonicalization versioning.</b> {@link #CANON_VERSION} identifies
 * the current algorithm. Every row records the version that produced its
 * hash in {@code canon_version}. When this constant changes, existing rows
 * are not tampered - they're stale - and the integrity check must report
 * the distinction rather than flagging them as mismatches.
 */
@Service
public class CanonicalJsonService {

    /**
     * Current canonicalization algorithm version. Bump this if the
     * serialization rules change (e.g. different number formatting,
     * different key ordering). Existing rows keep their original version.
     */
    public static final String CANON_VERSION = "v1";

    private final ObjectMapper objectMapper;

    public CanonicalJsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(JsonNode payload) {
        try {
            Object plain = objectMapper.treeToValue(payload, Object.class);
            byte[] bytes = canonicalMapper().writeValueAsBytes(plain);
            return sha256Hex(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash payload", e);
        }
    }

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