package com.runledger.repository;

import com.runledger.entity.SavedSearch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SavedSearchRepository extends JpaRepository<SavedSearch, Long> {

    /**
     * List all of a user's saved searches, newest first.
     * Used when no batch is specified.
     */
    List<SavedSearch> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    /**
     * Find a specific saved search by name and batch, scoped to owner.
     */
    Optional<SavedSearch> findByOwnerIdAndNameAndBatch(UUID ownerId, String name, String batch);

    /**
     * Find the most recent saved search with this name, regardless of batch,
     * scoped to owner. Used when a name lookup is made without a batch.
     */
    Optional<SavedSearch> findFirstByOwnerIdAndNameOrderByCreatedAtDesc(UUID ownerId, String name);

    /**
     * List saved searches scoped to the given batch, plus any batchless ones
     * the same owner has saved. Explicit JPQL because the equivalent derived
     * method name mixes AND and OR in a way Spring Data's parser can't
     * parenthesize correctly.
     */
    @Query("""
            SELECT s FROM SavedSearch s
            WHERE s.ownerId = :ownerId
              AND (s.batch = :batch OR (:batch IS NULL AND s.batch IS NULL))
            """)
    List<SavedSearch> findByOwnerIdAndBatchOrNull(
            @Param("ownerId") UUID ownerId,
            @Param("batch") String batch);

    @Transactional
    void deleteByOwnerIdAndNameAndBatch(UUID ownerId, String name, String batch);

    @Transactional
    void deleteByOwnerIdAndNameAndBatchIsNull(UUID ownerId, String name);
}