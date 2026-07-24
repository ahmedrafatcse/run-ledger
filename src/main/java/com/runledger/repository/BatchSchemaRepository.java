package com.runledger.repository;

import com.runledger.entity.BatchSchema;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BatchSchemaRepository extends JpaRepository<BatchSchema, String> {
    Optional<BatchSchema> findByBatch(String batch);
}