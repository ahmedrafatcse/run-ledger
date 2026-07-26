package com.runledger.repository;

import com.runledger.entity.Run;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class RunRepositoryCustomImpl implements RunRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public Page<Run> findByCompoundFilter(String sql, Map<String, Object> params,
                                          String countSql, Pageable pageable) {
        // Count query
        Query countQuery = entityManager.createNativeQuery(countSql);
        params.forEach(countQuery::setParameter);

        Object rawTotal = countQuery.getSingleResult();
        long total = rawTotal == null ? 0L : ((Number) rawTotal).longValue();

        // Data query with pagination
        Query dataQuery = entityManager.createNativeQuery(sql, Run.class);
        params.forEach(dataQuery::setParameter);

        if (pageable.isPaged()) {
            dataQuery.setFirstResult((int) pageable.getOffset());
            dataQuery.setMaxResults(pageable.getPageSize());
        }

        List<Run> content = dataQuery.getResultList();
        return new PageImpl<>(content, pageable, total);
    }
}