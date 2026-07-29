package com.runledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

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

    // ── New identity & version columns (V6) ──

    /** Original file name this run was ingested from. Extracted from payload._source.file. */
    @Column(name = "source_file", nullable = false)
    private String sourceFile = "";          // default prevents null on direct JPA save

    /** 0‑based index if the source file contained a top‑level JSON array. */
    @Column(name = "source_index", nullable = false)
    private int sourceIndex = 0;

    /** Monotonically increasing version number for the same (batch, source_file, source_index). */
    @Column(name = "version", nullable = false)
    private int version = 1;

    /**
     * SHA‑256 hex digest of the canonical (key‑sorted, compact) JSON payload.
     * Used for fast equality checks on re‑scan. Backfilled at startup for existing rows.
     */
    @Column(name = "payload_hash")
    private String payloadHash;

    /**
     * True for the most recent version of a run identity.
     * Older versions are marked false during ingestion.
     * Search queries always filter to latest = true.
     */
    @Column(name = "latest", nullable = false)
    private boolean latest = true;
}