package com.runledger.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "batch_schema")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchSchema {

    @Id
    @Column(name = "batch", nullable = false, unique = true)
    private String batch;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_mapping", columnDefinition = "jsonb", nullable = false)
    private String keyMapping;   // stored as JSON string

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}