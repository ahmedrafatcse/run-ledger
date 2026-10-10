package com.runledger.service;

import com.runledger.entity.SavedSearch;
import com.runledger.repository.SavedSearchRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.security.SecuredTransactionTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * CRUD for saved searches, scoped to the current user.
 *
 * <p>As of Slice 4.7, every read and write is owner-scoped: the caller's
 * user ID comes from {@link AppSecurityContext} and is used as the key
 * dimension alongside {@code (name, batch)}. Alice cannot see or overwrite
 * Bob's saved search of the same name; the two are distinct rows.
 *
 * <p>Until V18 lands (Slice 4.8), this scoping is enforced only at the
 * application layer. Once RLS is enabled on {@code saved_search}, the same
 * scoping will also be enforced by the database — belt and suspenders.
 *
 * <p>Owner is a user, not a team. Saved searches are personal; the review
 * of this decision favored user-owned for simplicity. Team-shared saved
 * searches would be a later column addition.
 */
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
     * {@code (owner, name, batch)}, then insert a new one with the current
     * owner.
     */
    public SavedSearch save(String name, String batch, String paramsJson) {
        return secured.execute(() -> {
            UUID ownerId = currentUserId();
            if (batch != null && !batch.isBlank()) {
                savedSearchRepository.deleteByOwnerIdAndNameAndBatch(ownerId, name, batch);
            } else {
                savedSearchRepository.deleteByOwnerIdAndNameAndBatchIsNull(ownerId, name);
            }
            SavedSearch ss = new SavedSearch();
            ss.setOwnerId(ownerId);
            ss.setName(name);
            ss.setBatch(batch);
            ss.setParamsJson(paramsJson);
            return savedSearchRepository.save(ss);
        });
    }

    /**
     * Load a saved search by name, scoped to the current owner.
     * Falls back to the most recent match when batch is not supplied.
     */
    public SavedSearch get(String name, String batch) {
        return secured.execute(() -> {
            UUID ownerId = currentUserId();
            if (batch != null && !batch.isBlank()) {
                return savedSearchRepository
                        .findByOwnerIdAndNameAndBatch(ownerId, name, batch)
                        .orElse(null);
            }
            return savedSearchRepository
                    .findFirstByOwnerIdAndNameOrderByCreatedAtDesc(ownerId, name)
                    .orElse(null);
        });
    }

    /**
     * List the current owner's saved searches.
     *
     * <p>When a batch is supplied, returns searches scoped to that batch
     * plus any batchless ones the owner has saved. When batch is null,
     * returns all of the owner's saved searches regardless of batch.
     */
    public List<SavedSearch> list(String batch) {
        return secured.execute(() -> {
            UUID ownerId = currentUserId();
            if (batch != null && !batch.isBlank()) {
                return savedSearchRepository.findByOwnerIdAndBatchOrNull(ownerId, batch);
            }
            return savedSearchRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        });
    }

    /**
     * Delete a saved search, scoped to the current owner. Attempting to
     * delete another user's search is a no-op (the delete finds no matching
     * row), not a permission error — matching the pattern elsewhere in the
     * app of "invisible means invisible."
     */
    public void delete(String name, String batch) {
        secured.execute(() -> {
            UUID ownerId = currentUserId();
            if (batch != null && !batch.isBlank()) {
                savedSearchRepository.deleteByOwnerIdAndNameAndBatch(ownerId, name, batch);
            } else {
                savedSearchRepository.deleteByOwnerIdAndNameAndBatchIsNull(ownerId, name);
            }
            return null;   // Supplier<Void> requires a return value
        });
    }

    // -----------------------------------------------------------------

    private UUID currentUserId() {
        return AppSecurityContext.require().userId();
    }
}