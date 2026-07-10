package com.runledger.repository;

import com.runledger.entity.Run;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RunRepository extends JpaRepository<Run, Long> {

    // ---------------------------------------------------------------
    // Original block‑search methods (no batch filter)
    // ---------------------------------------------------------------

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric > :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric > :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricGreaterThan(@Param("metric") String metric,
                                      @Param("value") double value,
                                      Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric >= :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric >= :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricGreaterThanOrEqual(@Param("metric") String metric,
                                             @Param("value") double value,
                                             Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric < :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric < :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricLessThan(@Param("metric") String metric,
                                   @Param("value") double value,
                                   Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric <= :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric <= :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricLessThanOrEqual(@Param("metric") String metric,
                                          @Param("value") double value,
                                          Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric = :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric = :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricEquals(@Param("metric") String metric,
                                 @Param("value") double value,
                                 Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.payload -> 'metrics' ->> :metric = :value
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.payload -> 'metrics' ->> :metric = :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricEqualsText(@Param("metric") String metric,
                                     @Param("value") String value,
                                     Pageable pageable);

    // ---------------------------------------------------------------
    // Batch‑filtered block‑search methods
    // ---------------------------------------------------------------

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric > :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric > :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricGreaterThanBatch(@Param("metric") String metric,
                                           @Param("value") double value,
                                           @Param("batch") String batch,
                                           Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric >= :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric >= :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricGreaterThanOrEqualBatch(@Param("metric") String metric,
                                                  @Param("value") double value,
                                                  @Param("batch") String batch,
                                                  Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric < :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric < :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricLessThanBatch(@Param("metric") String metric,
                                        @Param("value") double value,
                                        @Param("batch") String batch,
                                        Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric <= :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric <= :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricLessThanOrEqualBatch(@Param("metric") String metric,
                                               @Param("value") double value,
                                               @Param("batch") String batch,
                                               Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric = :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric = :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricEqualsBatch(@Param("metric") String metric,
                                      @Param("value") double value,
                                      @Param("batch") String batch,
                                      Pageable pageable);

    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.payload -> 'metrics' ->> :metric = :value
          AND r.batch = :batch
        ORDER BY r.created_at DESC
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.payload -> 'metrics' ->> :metric = :value
          AND r.batch = :batch
        """,
            nativeQuery = true)
    Page<Run> findByMetricEqualsTextBatch(@Param("metric") String metric,
                                          @Param("value") String value,
                                          @Param("batch") String batch,
                                          Pageable pageable);

    // ---------------------------------------------------------------
    // Metric key discovery
    // ---------------------------------------------------------------

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

    // Batch‑only listing (derived query – uses entity property name)
    Page<Run> findByBatch(String batch, Pageable pageable);
}