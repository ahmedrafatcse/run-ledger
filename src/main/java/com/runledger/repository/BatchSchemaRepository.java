package com.runledger.repository;

import com.runledger.entity.BatchSchema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BatchSchemaRepository extends JpaRepository<BatchSchema, Long> {

    /**
     * The canonical lookup. There is exactly one mapping row per
     * {@code (teamId, batch)} pair -- enforced by a unique constraint.
     */
    Optional<BatchSchema> findByTeamIdAndBatch(UUID teamId, String batch);
}