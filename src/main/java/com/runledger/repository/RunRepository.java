package com.runledger.repository;

import com.runledger.entity.Run;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunRepository extends JpaRepository<Run, Long> {

    // ---------------------------------------------------------------
    // Greater than
    // ---------------------------------------------------------------
    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric > :value
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric > :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricGreaterThan(@Param("metric") String metric,
                                      @Param("value") double value,
                                      Pageable pageable);

    // ---------------------------------------------------------------
    // Greater than or equal
    // ---------------------------------------------------------------
    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric >= :value
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric >= :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricGreaterThanOrEqual(@Param("metric") String metric,
                                             @Param("value") double value,
                                             Pageable pageable);

    // ---------------------------------------------------------------
    // Less than
    // ---------------------------------------------------------------
    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric < :value
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric < :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricLessThan(@Param("metric") String metric,
                                   @Param("value") double value,
                                   Pageable pageable);

    // ---------------------------------------------------------------
    // Less than or equal
    // ---------------------------------------------------------------
    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric <= :value
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric <= :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricLessThanOrEqual(@Param("metric") String metric,
                                          @Param("value") double value,
                                          Pageable pageable);

    // ---------------------------------------------------------------
    // Equals (numeric)
    // ---------------------------------------------------------------
    @Query(value = """
        SELECT r.* FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric = :value
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE (r.payload -> 'metrics' ->> :metric)::numeric = :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricEquals(@Param("metric") String metric,
                                 @Param("value") double value,
                                 Pageable pageable);

    // ---------------------------------------------------------------
    // Equals (text) – case‑sensitive exact match
    // ---------------------------------------------------------------
    @Query(value = """
        SELECT r.* FROM run r
        WHERE r.payload -> 'metrics' ->> :metric = :value
        """,
            countQuery = """
        SELECT count(*) FROM run r
        WHERE r.payload -> 'metrics' ->> :metric = :value
        """,
            nativeQuery = true)
    Page<Run> findByMetricEqualsText(@Param("metric") String metric,
                                     @Param("value") String value,
                                     Pageable pageable);
}