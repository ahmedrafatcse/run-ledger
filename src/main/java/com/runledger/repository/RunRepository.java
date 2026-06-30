package com.runledger.repository;

// database access interface for the Run table, and Spring automatically generates CRUD methods
// MERN equivalent = model methods

import com.runledger.entity.Run;
import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data JPA automatically provides save(), findById(), findAll(), deleteById(), etc.
public interface RunRepository extends JpaRepository<Run, Long> {
}