package com.runledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-team, per-batch shorthand-key → list-of-paths mapping.
 *
 * <p>Keyed by a surrogate {@code id}; the uniqueness invariant is
 * {@code (teamId, batch)}, enforced by a database unique constraint.
 * A surrogate key rather than a composite one because JPA composite keys
 * require an {@code @IdClass} or {@code @EmbeddedId}, and the constraint
 * gives the same enforcement with a one-line entity change.
 */
@Entity
@Table(name = "batch_schema")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchSchema {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "batch", nullable = false)
    private String batch;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_mapping", columnDefinition = "jsonb")
    private Map<String, List<String>> keyMapping;
}