package com.runledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "run")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Optional batch identifier (e.g. folder name) to group related runs. */
    @Column(name = "batch")
    private String batch;

    // ── Identity & version columns (V6) ──

    @Column(name = "source_file", nullable = false)
    private String sourceFile = "";

    @Column(name = "source_index", nullable = false)
    private int sourceIndex = 0;

    @Column(name = "version", nullable = false)
    private int version = 1;

    /** SHA-256 hex digest of the canonical (key-sorted, compact) JSON payload. */
    @Column(name = "payload_hash")
    private String payloadHash;

    /**
     * Which version of the canonicalization algorithm produced {@link #payloadHash}.
     * Distinct from a hash mismatch: a row with canon_version=v1 and a current
     * algorithm of v2 is not tampered, it's stale. Set on insert from
     * CanonicalJsonService.CANON_VERSION.
     */
    @Column(name = "canon_version", nullable = false)
    private String canonVersion = "v1";

    @Column(name = "latest", nullable = false)
    private boolean latest = true;

    // ── Team ownership & attribution columns (V10) ──

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    // ── Read-only relations for display (Pre.2) ──

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", insertable = false, updatable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", insertable = false, updatable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private AppUser submittedBy;

    /**
     * Short prefix of the payload hash for display. Returns the full hash
     * if it is 16 characters or fewer, and {@code "—"} if null.
     *
     * <p>Guards the detail template against a short or missing hash. The
     * template used to call {@code #strings.substring(run.payloadHash, 0, 16)}
     * directly, which threw {@code StringIndexOutOfBoundsException} on any
     * hash shorter than 16 characters. Ingestion writes a 64-char SHA-256
     * so this never fired in production, but a seed, import, or migration
     * with a short hash made {@code /runs/{id}} return 500.
     *
     * <p>Not a persisted column — no {@code @Column} annotation. Lombok's
     * {@code @Data} does not generate a getter for a name that has no
     * matching field, so this method name does not collide.
     */
    public String getPayloadHashPrefix() {
        if (payloadHash == null) {
            return "—";
        }
        return payloadHash.length() > 16
                ? payloadHash.substring(0, 16) + "…"
                : payloadHash;
    }
}