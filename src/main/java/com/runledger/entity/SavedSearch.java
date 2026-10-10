package com.runledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A saved search configuration, scoped to the user who created it.
 *
 * <p>Owner is a user, not a team. Saved searches are personal; the review
 * of this decision favored user-owned for simplicity. Team-shared saved
 * searches would be a later column addition.
 *
 * <p>Owner scoping is enforced at two layers as of Slice 4.8: the service
 * passes the current user's ID on every read and write, and the RLS policy
 * on this table scopes rows by {@code owner_id = current_setting(...)}.
 */
@Entity
@Table(name = "saved_search")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavedSearch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    @Column(name = "batch")
    private String batch;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_json", columnDefinition = "jsonb", nullable = false)
    private String paramsJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}