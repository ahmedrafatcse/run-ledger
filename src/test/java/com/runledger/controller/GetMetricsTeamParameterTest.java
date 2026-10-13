package com.runledger.controller;

import com.runledger.config.SecurityConfig;
import com.runledger.entity.Team;
import com.runledger.exception.GlobalExceptionHandler;
import com.runledger.repository.RunRepository;
import com.runledger.repository.TeamRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.security.IdentityFilter;
import com.runledger.security.SessionIdentityFilter;
import com.runledger.service.RunIngestionService;
import com.runledger.service.RunQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Covers the {@code ?team=} handling on {@code /api/runs/metrics}.
 *
 * <p>Behaviors:
 * <ul>
 *   <li>Researcher without {@code team} -- team derived from identity.</li>
 *   <li>Supervisor without {@code team} -- 400 with candidate teams.</li>
 *   <li>Supervisor with a valid {@code team} -- passes through.</li>
 * </ul>
 */
@WebMvcTest(RunController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class GetMetricsTeamParameterTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RunRepository runRepository;
    @MockitoBean private RunQueryService runQueryService;
    @MockitoBean private RunIngestionService ingestionService;
    @MockitoBean private IdentityFilter identityFilter;
    @MockitoBean private SessionIdentityFilter sessionIdentityFilter;
    @MockitoBean private TeamRepository teamRepository;

    private static final UUID TEAM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @AfterEach
    void clearContext() {
        AppSecurityContext.clear();
    }

    @Test
    void researcher_withoutTeam_derivesFromIdentity() throws Exception {
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(
                UUID.randomUUID(), "researcher", TEAM_A));
        when(runQueryService.getAvailableMetrics(eq(TEAM_A), eq("b")))
                .thenReturn(List.of("accuracy"));

        mockMvc.perform(get("/api/runs/metrics").param("batch", "b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("accuracy"));
    }

    @Test
    void supervisor_withoutTeam_returns400WithCandidates() throws Exception {
        UUID supervisorId = UUID.randomUUID();
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(
                supervisorId, "supervisor", null));

        Team teamA = new Team();
        teamA.setId(TEAM_A);
        teamA.setName("Team A");
        when(teamRepository.findAssignedTeams(supervisorId)).thenReturn(List.of(teamA));

        mockMvc.perform(get("/api/runs/metrics").param("batch", "b"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Team required"))
                .andExpect(jsonPath("$.availableTeams[0].id").value(TEAM_A.toString()))
                .andExpect(jsonPath("$.availableTeams[0].name").value("Team A"));
    }

    @Test
    void supervisor_withTeam_passesThrough() throws Exception {
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(
                UUID.randomUUID(), "supervisor", null));
        when(runQueryService.getAvailableMetrics(eq(TEAM_A), eq("b")))
                .thenReturn(List.of("accuracy", "loss"));

        mockMvc.perform(get("/api/runs/metrics")
                        .param("batch", "b")
                        .param("team", TEAM_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("accuracy"))
                .andExpect(jsonPath("$[1]").value("loss"));
    }
}