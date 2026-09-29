package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CanonicalJsonService service = new CanonicalJsonService(mapper);

    @Test
    void hash_isOrderIndependent() throws Exception {
        JsonNode a = mapper.readTree("{\"a\":1,\"b\":2}");
        JsonNode b = mapper.readTree("{\"b\":2,\"a\":1}");
        assertThat(service.hash(a)).isEqualTo(service.hash(b));
    }

    @Test
    void nestedObjectKeys_areSortedAtEveryLevel() throws Exception {
        JsonNode a = mapper.readTree("{\"outer\":{\"z\":1,\"a\":2},\"top\":1}");
        JsonNode b = mapper.readTree("{\"top\":1,\"outer\":{\"a\":2,\"z\":1}}");
        assertThat(service.hash(a)).isEqualTo(service.hash(b));
    }

    @Test
    void hash_isFormattingIndependent() {
        String compact = "{\"accuracy\":0.95,\"loss\":0.12}";
        String pretty = "{\n  \"accuracy\": 0.95,\n  \"loss\": 0.12\n}";
        assertThat(service.hash(compact)).isEqualTo(service.hash(pretty));
    }

    @Test
    void hash_ofNode_and_hash_ofString_areEqual() throws Exception {
        String json = "{\"accuracy\":0.95,\"loss\":0.12}";
        JsonNode node = mapper.readTree(json);
        assertThat(service.hash(node)).isEqualTo(service.hash(json));
    }

    @Test
    void differentContent_producesDifferentHashes() {
        assertThat(service.hash("{\"a\":1}"))
                .isNotEqualTo(service.hash("{\"a\":2}"));
    }

    @Test
    void arrays_arePreserved() {
        String withArray = "{\"results\":[{\"cacc\":0.9},{\"cacc\":0.8}]}";
        String reorderedArray = "{\"results\":[{\"cacc\":0.9},{\"cacc\":0.8}]}";
        assertThat(service.hash(withArray)).isEqualTo(service.hash(reorderedArray));
    }
}