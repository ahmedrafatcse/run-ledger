package com.runledger.repository;

import com.runledger.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    /**
     * Returns the teams a supervisor is assigned to.
     *
     * <p>Native query rather than a JPA relation because there's no
     * {@code SupervisorTeamAssignment} entity — the assignments table is
     * a pure join table and doesn't need one. The alternative would be a
     * full entity and repository for a two-column table, which adds more
     * code than it removes.
     *
     * <p>Runs under RLS as the querying identity. If a supervisor were to
     * call this for another supervisor's ID, the {@code app_users} lookup
     * that drives the policy would still resolve to themselves and the
     * result would be empty — which is the correct behavior.
     */
    @Query(value = """
            SELECT t.*
            FROM teams t
            JOIN supervisor_team_assignments s ON s.team_id = t.id
            WHERE s.supervisor_id = :supervisorId
            ORDER BY t.name
            """, nativeQuery = true)
    List<Team> findAssignedTeams(@Param("supervisorId") UUID supervisorId);
}