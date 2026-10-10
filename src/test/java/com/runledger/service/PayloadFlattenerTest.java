package com.runledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.FieldDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadFlattenerTest {

    private final PayloadFlattener flattener =
            new PayloadFlattener(new ObjectMapper());

    @Test
    void flatten_flatObject_producesFlatMap() {
        Map<String, String> flat = flattener.flatten("{\"a\":1,\"b\":\"x\"}");

        assertThat(flat).containsEntry("a", "1");
        assertThat(flat).containsEntry("b", "x");
    }

    @Test
    void flatten_nestedObject_producesDotPaths() {
        Map<String, String> flat = flattener.flatten(
                "{\"config\":{\"optimizer\":{\"name\":\"AdamW\"}}}");

        assertThat(flat).containsEntry("config.optimizer.name", "AdamW");
    }

    @Test
    void flatten_array_producesBracketIndices() {
        Map<String, String> flat = flattener.flatten(
                "{\"results\":[{\"acc\":0.9},{\"acc\":0.95}]}");

        assertThat(flat).containsEntry("results[0].acc", "0.9");
        assertThat(flat).containsEntry("results[1].acc", "0.95");
    }

    @Test
    void diff_identicalPayloads_returnsEmpty() {
        List<FieldDiff> diffs = flattener.diff(
                "{\"a\":1,\"b\":2}",
                "{\"a\":1,\"b\":2}");

        assertThat(diffs).isEmpty();
    }

    @Test
    void diff_singleFieldChanged_returnsOneEntry() {
        // JSON numbers are normalized by Jackson's asText(): 0.90 becomes
        // "0.9". This is deliberate — the diff compares semantic values,
        // not formatting. Two payloads that differ only in trailing zeros
        // are not a change worth reporting.
        List<FieldDiff> diffs = flattener.diff(
                "{\"accuracy\":0.90,\"loss\":0.15}",
                "{\"accuracy\":0.94,\"loss\":0.15}");

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).path()).isEqualTo("accuracy");
        assertThat(diffs.get(0).oldValue()).isEqualTo("0.9");
        assertThat(diffs.get(0).newValue()).isEqualTo("0.94");
        assertThat(diffs.get(0).type()).isEqualTo(FieldDiff.ChangeType.CHANGED);
    }

    @Test
    void diff_fieldAdded_marksAdded() {
        List<FieldDiff> diffs = flattener.diff(
                "{\"accuracy\":0.90}",
                "{\"accuracy\":0.90,\"notes\":\"new\"}");

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).path()).isEqualTo("notes");
        assertThat(diffs.get(0).type()).isEqualTo(FieldDiff.ChangeType.ADDED);
        assertThat(diffs.get(0).oldValue()).isNull();
    }

    @Test
    void diff_fieldRemoved_marksRemoved() {
        List<FieldDiff> diffs = flattener.diff(
                "{\"accuracy\":0.90,\"notes\":\"old\"}",
                "{\"accuracy\":0.90}");

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).path()).isEqualTo("notes");
        assertThat(diffs.get(0).type()).isEqualTo(FieldDiff.ChangeType.REMOVED);
        assertThat(diffs.get(0).newValue()).isNull();
    }

    @Test
    void diff_nestedFieldChanged_pathIsDotNotation() {
        List<FieldDiff> diffs = flattener.diff(
                "{\"config\":{\"lr\":0.001}}",
                "{\"config\":{\"lr\":0.0001}}");

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).path()).isEqualTo("config.lr");
    }

    @Test
    void diff_arrayElementChanged_pathIncludesIndex() {
        List<FieldDiff> diffs = flattener.diff(
                "{\"results\":[{\"acc\":0.9},{\"acc\":0.95}]}",
                "{\"results\":[{\"acc\":0.9},{\"acc\":0.99}]}");

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).path()).isEqualTo("results[1].acc");
        assertThat(diffs.get(0).oldValue()).isEqualTo("0.95");
        assertThat(diffs.get(0).newValue()).isEqualTo("0.99");
    }

    @Test
    void diff_multipleChanges_sortedByPath() {
        List<FieldDiff> diffs = flattener.diff(
                "{\"z\":1,\"a\":1,\"m\":1}",
                "{\"z\":2,\"a\":2,\"m\":2}");

        assertThat(diffs).extracting(FieldDiff::path)
                .containsExactly("a", "m", "z");
    }
}