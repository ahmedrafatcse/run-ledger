package com.runledger.service;

import com.runledger.entity.SavedSearch;
import com.runledger.repository.SavedSearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SavedSearchService {

    private final SavedSearchRepository savedSearchRepository;

    public SavedSearchService(SavedSearchRepository savedSearchRepository) {
        this.savedSearchRepository = savedSearchRepository;
    }

    @Transactional
    public SavedSearch save(String name, String batch, String paramsJson) {
        // existing logic unchanged
        if (batch != null && !batch.isBlank()) {
            savedSearchRepository.deleteByNameAndBatch(name, batch);
        } else {
            savedSearchRepository.deleteByNameAndBatchIsNull(name);
        }
        SavedSearch ss = new SavedSearch();
        ss.setName(name);
        ss.setBatch(batch);
        ss.setParamsJson(paramsJson);
        return savedSearchRepository.save(ss);
    }

    public SavedSearch get(String name, String batch) {
        if (batch != null && !batch.isBlank()) {
            return savedSearchRepository.findByNameAndBatch(name, batch).orElse(null);
        }
        // Without batch, return the most recent saved search with this name
        return savedSearchRepository.findFirstByNameOrderByCreatedAtDesc(name).orElse(null);
    }

    public List<SavedSearch> list(String batch) {
        if (batch != null && !batch.isBlank()) {
            return savedSearchRepository.findByBatchOrBatchIsNull(batch);
        }
        return savedSearchRepository.findAll();
    }

    @Transactional
    public void delete(String name, String batch) {
        if (batch != null && !batch.isBlank()) {
            savedSearchRepository.deleteByNameAndBatch(name, batch);
        } else {
            savedSearchRepository.deleteByNameAndBatchIsNull(name);
        }
    }
}