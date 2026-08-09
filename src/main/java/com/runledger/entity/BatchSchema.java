package com.runledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "batch_schema")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchSchema {

    @Id
    @Column(name = "batch", nullable = false, unique = true)
    private String batch;

    /**
     * Shorthand key → list of full dot‑paths.
     * The first entry in each list is the shallowest occurrence.
     *
     * <pre>
     * {
     *   "pos": [
     *     "mode2.per_sample_top_sites[].genuine_best.pos",
     *     "mode2.per_sample_top_sites[].top_sites[].pos"
     *   ],
     *   "layer": [
     *     "mode2.per_sample_top_sites[].genuine_best.layer"
     *   ]
     * }
     * </pre>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_mapping", columnDefinition = "jsonb", nullable = false)
    private Map<String, List<String>> keyMapping;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}