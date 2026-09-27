package com.runledger.service;

import com.runledger.entity.SavedSearch;
import com.runledger.repository.SavedSearchRepository;
import com.runledger.security.SecuredTransactionTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SavedSearchService {

    private final SavedSearchRepository savedSearchRepository;
    private final SecuredTransactionTemplate secured;

    public SavedSearchService(SavedSearchRepository savedSearchRepository,
                              SecuredTransactionTemplate secured) {
        this.savedSearchRepository = savedSearchRepository;
        this.secured = secured;
    }

    /**
     * Save (upsert) a saved search: delete any existing row with the same
     * name+batch, then insert a new one.
     *
     * <p>Wrapped in {@link SecuredTransactionTemplate} so both the delete and
     * the insert happen inside one identity-scoped transaction.
     */
    public SavedSearch save(String name, String batch, String paramsJson) {
        return secured.execute(() -> {
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
        });
    }

    public SavedSearch get(String name, String batch) {
        return secured.execute(() -> {
            if (batch != null && !batch.isBlank()) {
                return savedSearchRepository.findByNameAndBatch(name, batch).orElse(null);
            }
            // Without batch, return the most recent saved search with this name
            return savedSearchRepository.findFirstByNameOrderByCreatedAtDesc(name).orElse(null);
        });
    }

    public List<SavedSearch> list(String batch) {
        return secured.execute(() -> {
            if (batch != null && !batch.isBlank()) {
                return savedSearchRepository.findByBatchOrBatchIsNull(batch);
            }
            return savedSearchRepository.findAll();
        });
    }

    public void delete(String name, String batch) {
        secured.execute(() -> {
            if (batch != null && !batch.isBlank()) {
                savedSearchRepository.deleteByNameAndBatch(name, batch);
            } else {
                savedSearchRepository.deleteByNameAndBatchIsNull(name);
            }
            return null;   // Supplier<Void> requires a return value
        });
    }
}