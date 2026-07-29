package com.runledger.repository;

import com.runledger.entity.SavedSearch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SavedSearchRepository extends JpaRepository<SavedSearch, Long> {
    Optional<SavedSearch> findByNameAndBatch(String name, String batch);
    Optional<SavedSearch> findByNameAndBatchIsNull(String name);
    Optional<SavedSearch> findFirstByNameOrderByCreatedAtDesc(String name);
    List<SavedSearch> findByBatchOrBatchIsNull(String batch);
    void deleteByNameAndBatch(String name, String batch);
    void deleteByNameAndBatchIsNull(String name);
}