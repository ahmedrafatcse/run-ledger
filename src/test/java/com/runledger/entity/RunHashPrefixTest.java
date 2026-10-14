package com.runledger.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detail template renders the hash prefix; this guards the three
 * shapes a hash can take: long (prefix + ellipsis), short (returned as-is),
 * and null (em dash). The template used to call substring(0, 16) directly,
 * which threw on short hashes — that was the 500 in RunDetailDepthTest.
 */
class RunHashPrefixTest {

    private static Run runWithHash(String hash) {
        Run r = new Run();
        r.setPayloadHash(hash);
        return r;
    }

    @Test
    void longHash_isTruncatedWithEllipsis() {
        Run r = runWithHash("0123456789abcdef0123456789abcdef");
        assertThat(r.getPayloadHashPrefix()).isEqualTo("0123456789abcdef…");
    }

    @Test
    void exactlySixteen_isReturnedWhole() {
        Run r = runWithHash("0123456789abcdef");
        assertThat(r.getPayloadHashPrefix()).isEqualTo("0123456789abcdef");
    }

    @Test
    void shortHash_isReturnedWhole() {
        Run r = runWithHash("h");
        assertThat(r.getPayloadHashPrefix()).isEqualTo("h");
    }

    @Test
    void nullHash_isEmDash() {
        Run r = runWithHash(null);
        assertThat(r.getPayloadHashPrefix()).isEqualTo("—");
    }
}