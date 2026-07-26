package com.runledger.repository;

import com.runledger.entity.Run;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;

public interface RunRepositoryCustom {
    Page<Run> findByCompoundFilter(String sql, Map<String, Object> params, String countSql, Pageable pageable);
}