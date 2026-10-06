package com.runledger.security;

/**
 * Result of a single-row integrity check.
 *
 * <p>Four states, not two:
 * <ul>
 *   <li>{@link State#VERIFIED} — hash recomputes cleanly under the current
 *       algorithm, matches the stored hash, and the stored canon_version
 *       matches the current constant.</li>
 *   <li>{@link State#TAMPERED} — the row was hashed with the current
 *       algorithm, but recomputing the hash from its payload doesn't match
 *       what's stored. Content has been changed since submission.</li>
 *   <li>{@link State#STALE} — the row was hashed with an older algorithm.
 *       It may or may not be tampered; this check can't tell, because the
 *       current algorithm isn't the one that produced the stored hash.
 *       Re-verification requires the algorithm version the row was signed
 *       with, which isn't retained here.</li>
 *   <li>{@link State#ERROR} — the payload can't be parsed, or hashing
 *       failed. Distinct from TAMPERED: a parse failure isn't evidence
 *       of tampering, it's evidence of a bad row.</li>
 * </ul>
 *
 * <p>The template renders the first two states as green/red badges. The
 * last two need different language — STALE means "can't verify with current
 * tooling," ERROR means "row is unreadable."
 */
public record IntegrityStatus(State state, String message) {

    public enum State {
        VERIFIED,
        TAMPERED,
        STALE,
        ERROR
    }

    public boolean isVerified() {
        return state == State.VERIFIED;
    }

    public boolean isTampered() {
        return state == State.TAMPERED;
    }
}