package com.runledger.controller;

import com.runledger.config.SecurityConfig;
import com.runledger.entity.AppUser;
import com.runledger.exception.GlobalExceptionHandler;
import com.runledger.repository.AppUserRepository;
import com.runledger.repository.TeamRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.security.IdentityFilter;
import com.runledger.service.RunQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import static org.mockito.Mockito.verify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice-level test for QueryController. Checks routing, model population,
 * and error handling for the aggregate mode.
 *
 * <p>Mirrors the annotation set used by {@link RunControllerTest}:
 * {@code @WebMvcTest} loads only the controller and its {@code @ControllerAdvice},
 * so {@code GlobalExceptionHandler} and {@code SecurityConfig} are imported
 * explicitly. Filters are bypassed via {@code addFilters=false}; the one
 * filter bean that the slice tries to construct is replaced with a mock.
 *
 * <p>A fake identity is bound to {@link AppSecurityContext} in
 * {@code @BeforeEach} because {@link QueryController} calls
 * {@code AppSecurityContext.require()}.
 */
@WebMvcTest(QueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class QueryControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RunQueryService runQueryService;
    @MockitoBean private AppUserRepository appUserRepository;
    @MockitoBean private TeamRepository teamRepository;
    @MockitoBean private IdentityFilter identityFilter;

    private UUID fakeUserId;
    private UUID fakeTeamId;

    @BeforeEach
    void setUp() {
        fakeUserId = UUID.randomUUID();
        fakeTeamId = UUID.randomUUID();

        AppUser user = new AppUser();
        user.setId(fakeUserId);
        user.setDisplayName("Test User");
        user.setAppRole("researcher");
        user.setTeamId(fakeTeamId);

        when(appUserRepository.findById(any())).thenReturn(Optional.of(user));
        when(teamRepository.findById(any())).thenReturn(Optional.empty());

        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(
                fakeUserId, "researcher", fakeTeamId));
    }

    @AfterEach
    void tearDown() {
        AppSecurityContext.clear();
    }

    // -----------------------------------------------------------------
    // No parameters — form renders, no results
    // -----------------------------------------------------------------

    @Test
    void aggregate_withNoParameters_rendersEmptyForm() throws Exception {
        mockMvc.perform(get("/query/aggregate"))
                .andExpect(status().isOk())
                .andExpect(view().name("query/aggregate"))
                .andExpect(model().attribute("hasQuery", false))
                .andExpect(model().attributeExists("currentUser"))
                .andExpect(model().attributeExists("assignedTeams"));
    }

    // -----------------------------------------------------------------
    // Valid parameters — results rendered
    // -----------------------------------------------------------------

    @Test
    void aggregate_withValidParameters_rendersResults() throws Exception {
        when(runQueryService.aggregate(eq("AVG"), eq("accuracy"), eq("experiment"), any()))
                .thenReturn(List.of(
                        Map.of("group", "demo", "result", 0.9266),
                        Map.of("group", "other", "result", 0.8800)));

        mockMvc.perform(get("/query/aggregate")
                        .param("agg", "AVG")
                        .param("metric", "accuracy")
                        .param("groupBy", "experiment"))
                .andExpect(status().isOk())
                .andExpect(view().name("query/aggregate"))
                .andExpect(model().attribute("hasQuery", true))
                .andExpect(model().attribute("agg", "AVG"))
                .andExpect(model().attribute("metric", "accuracy"))
                .andExpect(model().attribute("groupBy", "experiment"))
                .andExpect(model().attributeExists("results"));
    }

    // -----------------------------------------------------------------
    // Invalid aggregate function — error rendered, not 500
    // -----------------------------------------------------------------

    @Test
    void aggregate_withInvalidFunction_rendersErrorNotThrows() throws Exception {
        when(runQueryService.aggregate(eq("BOGUS"), eq("accuracy"), any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "Unsupported aggregate: BOGUS. Allowed: [AVG, MAX, MIN, SUM, COUNT]"));

        mockMvc.perform(get("/query/aggregate")
                        .param("agg", "BOGUS")
                        .param("metric", "accuracy"))
                .andExpect(status().isOk())
                .andExpect(view().name("query/aggregate"))
                .andExpect(model().attributeExists("queryError"));
    }

    // -----------------------------------------------------------------
    // COUNT with no metric — valid case, metric isn't required for COUNT
    // -----------------------------------------------------------------

    @Test
    void aggregate_countWithoutMetric_isAccepted() throws Exception {
        when(runQueryService.aggregate(eq("COUNT"), any(), any(), any()))
                .thenReturn(List.of(Map.of("result", 4.0)));

        mockMvc.perform(get("/query/aggregate")
                        .param("agg", "COUNT"))
                .andExpect(status().isOk())
                .andExpect(view().name("query/aggregate"))
                .andExpect(model().attribute("hasQuery", true));
    }
    // -----------------------------------------------------------------
    // Phrase / fuzzy
    // -----------------------------------------------------------------

    @Test
    void phrase_withNoQuery_rendersEmptyForm() throws Exception {
        mockMvc.perform(get("/query/phrase"))
                .andExpect(status().isOk())
                .andExpect(view().name("query/phrase"))
                .andExpect(model().attribute("hasQuery", false));
    }

    @Test
    void phrase_withQuery_usesPhraseSearch() throws Exception {
        when(runQueryService.searchByPhrase(eq("weighted"), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/query/phrase").param("q", "weighted"))
                .andExpect(status().isOk())
                .andExpect(view().name("query/phrase"))
                .andExpect(model().attribute("hasQuery", true))
                .andExpect(model().attribute("fuzzy", false));

        verify(runQueryService).searchByPhrase(eq("weighted"), any(Pageable.class));
    }

    @Test
    void phrase_withFuzzyFlag_usesFuzzySearch() throws Exception {
        when(runQueryService.searchByFuzzy(eq("avaraging"), eq(0.3), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/query/phrase")
                        .param("q", "avaraging")
                        .param("fuzzy", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("fuzzy", true));

        verify(runQueryService).searchByFuzzy(eq("avaraging"), eq(0.3), any(Pageable.class));
    }

    @Test
    void phrase_withBatch_usesBatchVariant() throws Exception {
        when(runQueryService.searchByPhrase(eq("weighted"), eq("demo-alice"), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/query/phrase")
                        .param("q", "weighted")
                        .param("batch", "demo-alice"))
                .andExpect(status().isOk());

        verify(runQueryService).searchByPhrase(eq("weighted"), eq("demo-alice"), any(Pageable.class));
    }

    // -----------------------------------------------------------------
    // Compare
    // -----------------------------------------------------------------

    @Test
    void compare_withNoConditions_rendersEmptyForm() throws Exception {
        mockMvc.perform(get("/query/compare"))
                .andExpect(status().isOk())
                .andExpect(view().name("query/compare"))
                .andExpect(model().attribute("hasQuery", false))
                .andExpect(model().attributeExists("filters"));
    }

    @Test
    void compare_withSingleCondition_runsQuery() throws Exception {
        when(runQueryService.queryByMultipleFilters(any(), any()))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/query/compare")
                        .param("metric", "accuracy")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("combine", "and"))
                .andExpect(status().isOk())
                .andExpect(view().name("query/compare"))
                .andExpect(model().attribute("hasQuery", true));

        verify(runQueryService).queryByMultipleFilters(any(), any());
    }

    @Test
    void compare_withMultipleConditions_runsCompoundQuery() throws Exception {
        when(runQueryService.queryByMultipleFilters(any(), any()))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/query/compare")
                        .param("metric", "accuracy", "loss")
                        .param("op", "gt", "lt")
                        .param("value", "0.9", "0.2")
                        .param("combine", "and"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasQuery", true))
                .andExpect(model().attribute("combine", "and"));

        verify(runQueryService).queryByMultipleFilters(any(), any());
    }

    @Test
    void compare_withIncompleteCondition_skipsIt() throws Exception {
        when(runQueryService.queryByMultipleFilters(any(), any()))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/query/compare")
                        .param("metric", "accuracy", "loss")
                        .param("op", "gt", "lt")
                        .param("value", "0.9", "")
                        .param("combine", "and"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasQuery", true));
    }
}

