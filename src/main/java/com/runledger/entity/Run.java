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

    /** Original file name this run was ingested from. Extracted from payload._source.file. */
    @Column(name = "source_file", nullable = false)
    private String sourceFile = "";

    /** 0-based index if the source file contained a top-level JSON array. */
    @Column(name = "source_index", nullable = false)
    private int sourceIndex = 0;

    /** Monotonically increasing version number for the same (batch, source_file, source_index). */
    @Column(name = "version", nullable = false)
    private int version = 1;

    /** SHA-256 hex digest of the canonical (key-sorted, compact) JSON payload. */
    @Column(name = "payload_hash")
    private String payloadHash;

    /** True for the most recent version of a run identity. */
    @Column(name = "latest", nullable = false)
    private boolean latest = true;

    // ── Team ownership & attribution columns (V10) ──

    /**
     * The team that owns this run. Set from the submitting user's identity
     * during ingestion, never from the request payload.
     */
    @Column(name = "team_id")
    private UUID teamId;

    /**
     * The user who submitted this run. Set from the resolved identity during
     * ingestion, never from the request payload.
     */
    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    // ── Read-only relations for display ──

    /**
     * The Team that owns this run. Read-only view of {@link #teamId}, marked
     * insertable=false/updatable=false so JPA doesn't try to write through
     * both the UUID column and the relation. Ingestion writes the UUID
     * directly; this field exists so templates and DTOs can reach the
     * team's name without a separate lookup.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", insertable = false, updatable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Team team;

    /**
     * The AppUser who submitted this run. Read-only view of {@link #uploadedBy},
     * same reasoning as {@link #team}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", insertable = false, updatable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private AppUser submittedBy;
}