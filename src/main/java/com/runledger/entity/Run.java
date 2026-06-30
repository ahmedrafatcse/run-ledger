package com.runledger.entity;
// represents rows in tables
// MERN equivalent = model (schema)

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

// a row in the run table
@Entity // this class represents a table in the db
@Table(name = "run") // select run table
@Data // auto-generates getters/setters, toString(), etc.
@NoArgsConstructor // hibernate needs this to create obj from db
@AllArgsConstructor // provides constructor with all fields
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // for JPA to know how ID is being generated
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;   // stored as raw JSON string, Postgres stores it as JSONB

    @CreationTimestamp
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;  // set automatically by DB (DEFAULT NOW())
}