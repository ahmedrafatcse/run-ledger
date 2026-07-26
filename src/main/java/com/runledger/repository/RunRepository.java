package com.runledger.repository;

import com.runledger.entity.Run;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RunRepository extends JpaRepository<Run, Long> {

    // ================================================================
    // Original block‑search methods (no batch filter)
    // ================================================================

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric > :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric > :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricGreaterThan(@Param("path") String path,
                                      @Param("value") double value,
                                      Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric >= :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric >= :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricGreaterThanOrEqual(@Param("path") String path,
                                             @Param("value") double value,
                                             Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric < :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric < :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricLessThan(@Param("path") String path,
                                   @Param("value") double value,
                                   Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric <= :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric <= :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricLessThanOrEqual(@Param("path") String path,
                                          @Param("value") double value,
                                          Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric = :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric = :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricEquals(@Param("path") String path,
                                 @Param("value") double value,
                                 Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.payload #>> string_to_array(:path, '.') = :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.payload #>> string_to_array(:path, '.') = :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricEqualsText(@Param("path") String path,
                                     @Param("value") String value,
                                     Pageable pageable);

    // ================================================================
    // Batch‑filtered block‑search methods
    // ================================================================

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric > :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric > :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricGreaterThanBatch(@Param("path") String path,
                                           @Param("value") double value,
                                           @Param("batch") String batch,
                                           Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric >= :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric >= :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricGreaterThanOrEqualBatch(@Param("path") String path,
                                                  @Param("value") double value,
                                                  @Param("batch") String batch,
                                                  Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric < :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric < :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricLessThanBatch(@Param("path") String path,
                                        @Param("value") double value,
                                        @Param("batch") String batch,
                                        Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric <= :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric <= :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricLessThanOrEqualBatch(@Param("path") String path,
                                               @Param("value") double value,
                                               @Param("batch") String batch,
                                               Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric = :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload #>> string_to_array(:path, '.'))::numeric = :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricEqualsBatch(@Param("path") String path,
                                      @Param("value") double value,
                                      @Param("batch") String batch,
                                      Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.payload #>> string_to_array(:path, '.') = :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.payload #>> string_to_array(:path, '.') = :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricEqualsTextBatch(@Param("path") String path,
                                          @Param("value") String value,
                                          @Param("batch") String batch,
                                          Pageable pageable);

    // ================================================================
    // Array‑aware search methods ([] → [*] translation handled by service)
    //
    // :path  – the jsonb path up to (but not including) the array wildcard
    //          e.g. "client.results[*]"  (already translated from [])
    // :leaf  – the field name inside each array element, e.g. "cacc"
    //
    // #>  returns jsonb → used for type checking
    // #>> returns text  → used for numeric cast / text comparison
    // ================================================================

    @Query(value = """
        SELECT r.* FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric > :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric > :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayGreaterThan(@Param("path") String path,
                                     @Param("leaf") String leaf,
                                     @Param("value") double value,
                                     Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric >= :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric >= :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayGreaterThanOrEqual(@Param("path") String path,
                                            @Param("leaf") String leaf,
                                            @Param("value") double value,
                                            Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric < :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric < :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayLessThan(@Param("path") String path,
                                  @Param("leaf") String leaf,
                                  @Param("value") double value,
                                  Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric <= :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric <= :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayLessThanOrEqual(@Param("path") String path,
                                         @Param("leaf") String leaf,
                                         @Param("value") double value,
                                         Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric = :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric = :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayEquals(@Param("path") String path,
                                @Param("leaf") String leaf,
                                @Param("value") double value,
                                Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE elem #>> string_to_array(:leaf, '.') = :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE elem #>> string_to_array(:leaf, '.') = :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayEqualsText(@Param("path") String path,
                                    @Param("leaf") String leaf,
                                    @Param("value") String value,
                                    Pageable pageable);

    // ================================================================
    // Batch‑filtered array‑aware search methods
    // ================================================================

    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric > :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric > :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayGreaterThanBatch(@Param("path") String path,
                                          @Param("leaf") String leaf,
                                          @Param("value") double value,
                                          @Param("batch") String batch,
                                          Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric >= :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric >= :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayGreaterThanOrEqualBatch(@Param("path") String path,
                                                 @Param("leaf") String leaf,
                                                 @Param("value") double value,
                                                 @Param("batch") String batch,
                                                 Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric < :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric < :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayLessThanBatch(@Param("path") String path,
                                       @Param("leaf") String leaf,
                                       @Param("value") double value,
                                       @Param("batch") String batch,
                                       Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric <= :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric <= :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayLessThanOrEqualBatch(@Param("path") String path,
                                              @Param("leaf") String leaf,
                                              @Param("value") double value,
                                              @Param("batch") String batch,
                                              Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric = :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE jsonb_typeof(elem #> string_to_array(:leaf, '.')) = 'number'
              AND (elem #>> string_to_array(:leaf, '.'))::numeric = :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayEqualsBatch(@Param("path") String path,
                                     @Param("leaf") String leaf,
                                     @Param("value") double value,
                                     @Param("batch") String batch,
                                     Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE elem #>> string_to_array(:leaf, '.') = :value
        )
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.batch = :batch
          AND EXISTS (
            SELECT 1 FROM jsonb_path_query(
                r.payload, ('$.' || :path)::jsonpath
            ) AS elem
            WHERE elem #>> string_to_array(:leaf, '.') = :value
        )
        """,
            nativeQuery = true)
    Page<Run> findByArrayEqualsTextBatch(@Param("path") String path,
                                         @Param("leaf") String leaf,
                                         @Param("value") String value,
                                         @Param("batch") String batch,
                                         Pageable pageable);

    // ================================================================
    // Metric key discovery
    // ================================================================

    @Query(value = """
        SELECT DISTINCT key
        FROM run,
        LATERAL jsonb_object_keys(payload->'metrics') AS k(key)
        """, nativeQuery = true)
    List<String> findDistinctMetricKeys();

    @Query(value = """
        SELECT DISTINCT key
        FROM run,
        LATERAL jsonb_object_keys(payload->'metrics') AS k(key)
        WHERE batch = :batch
        """, nativeQuery = true)
    List<String> findDistinctMetricKeysByBatch(@Param("batch") String batch);

    // Batch‑only listing
    Page<Run> findByBatch(String batch, Pageable pageable);

    // ================================================================
    // Full‑text phrase search (over entire JSONB payload)
    // ================================================================

    @Query(value = """
        SELECT r.* FROM run r
        WHERE jsonb_to_tsvector('english', r.payload, '"all"')
               @@ phraseto_tsquery('english'::regconfig, cast(:phrase as text))
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE jsonb_to_tsvector('english', r.payload, '"all"')
               @@ phraseto_tsquery('english'::regconfig, cast(:phrase as text))
        """,
            nativeQuery = true)
    Page<Run> searchByPhrase(@Param("phrase") String phrase,
                             Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE jsonb_to_tsvector('english', r.payload, '"all"')
               @@ phraseto_tsquery('english'::regconfig, cast(:phrase as text))
          AND r.batch = :batch
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE jsonb_to_tsvector('english', r.payload, '"all"')
               @@ phraseto_tsquery('english'::regconfig, cast(:phrase as text))
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> searchByPhraseBatch(@Param("phrase") String phrase,
                                  @Param("batch") String batch,
                                  Pageable pageable);

    // ================================================================
    // Fuzzy trigram search (over entire JSONB payload using word_similarity)
    // ================================================================

    @Query(value = """
        SELECT r.* FROM run r
        WHERE word_similarity(:term, r.payload::text) > :threshold
        ORDER BY word_similarity(:term, r.payload::text) DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE word_similarity(:term, r.payload::text) > :threshold
        """,
            nativeQuery = true)
    Page<Run> searchByFuzzy(@Param("term") String term,
                            @Param("threshold") double threshold,
                            Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE word_similarity(:term, r.payload::text) > :threshold
          AND r.batch = :batch
        ORDER BY word_similarity(:term, r.payload::text) DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE word_similarity(:term, r.payload::text) > :threshold
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> searchByFuzzyBatch(@Param("term") String term,
                                 @Param("threshold") double threshold,
                                 @Param("batch") String batch,
                                 Pageable pageable);
}