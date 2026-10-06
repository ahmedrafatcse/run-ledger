package com.runledger.security;

import com.runledger.entity.Run;
import com.runledger.service.CanonicalJsonService;
import org.springframework.stereotype.Service;

/**
 * Fast, single-row integrity check for display purposes.
 *
 * <p>Recomputes the payload hash from the stored payload and compares it to
 * the stored hash. If they match and the row's {@code canon_version} matches
 * the current algorithm, the row is verified. Otherwise, the status explains
 * which case it is.
 *
 * <p><b>This is not {@code runledger verify}.</b> That command walks the
 * entire chain for a team from genesis, detects deletion and reordering
 * across the history, and is correspondingly not cheap. This check answers
 * one narrow question — "does this row's content match its own recorded
 * hash, right now" — which is cheap enough to run on every page load. It
 * cannot detect a privileged attacker who rewrote both the payload and the
 * stored hash consistently, because it has no reference point outside the
 * row itself.
 */
@Service
public class IntegrityStatusService {

    private final CanonicalJsonService canonicalJson;

    public IntegrityStatusService(CanonicalJsonService canonicalJson) {
        this.canonicalJson = canonicalJson;
    }

    /**
     * Check a single run's integrity.
     *
     * <p>Order matters: canon_version is checked before the hash comparison.
     * A row hashed under a different algorithm will fail the current
     * algorithm's comparison even when its content is unchanged, so the
     * version mismatch must be reported as STALE before the hash comparison
     * gets a chance to misreport it as TAMPERED.
     */
    public IntegrityStatus check(Run run) {
        if (run == null) {
            return new IntegrityStatus(IntegrityStatus.State.ERROR,
                    "No run provided.");
        }

        String storedHash = run.getPayloadHash();
        String storedVersion = run.getCanonVersion();

        if (storedHash == null || storedHash.isBlank()) {
            return new IntegrityStatus(IntegrityStatus.State.ERROR,
                    "Run has no recorded hash; cannot verify.");
        }

        // Check canon_version first. A mismatch means the row was signed
        // with a different algorithm than the one running now — the hash
        // can't be meaningfully compared until that's accounted for.
        if (!CanonicalJsonService.CANON_VERSION.equals(storedVersion)) {
            return new IntegrityStatus(IntegrityStatus.State.STALE,
                    "This run was hashed under canonicalization "
                            + storedVersion + "; the current algorithm is "
                            + CanonicalJsonService.CANON_VERSION
                            + ". Cannot verify without the original algorithm.");
        }

        // Recompute from the stored payload. This is the actual check.
        // Comparing storedHash to itself here would be the classic bug —
        // every row would pass, including tampered ones.
        String recomputed;
        try {
            recomputed = canonicalJson.hash(run.getPayload());
        } catch (Exception e) {
            return new IntegrityStatus(IntegrityStatus.State.ERROR,
                    "Payload could not be parsed for verification: "
                            + e.getMessage());
        }

        if (!recomputed.equals(storedHash)) {
            return new IntegrityStatus(IntegrityStatus.State.TAMPERED,
                    "Content does not match its recorded hash. "
                            + "This row has been modified since submission.");
        }

        return new IntegrityStatus(IntegrityStatus.State.VERIFIED,
                "Content matches its recorded hash.");
    }
}