package com.runledger.controller;

import com.runledger.entity.AppUser;
import com.runledger.entity.Team;
import com.runledger.repository.AppUserRepository;
import com.runledger.repository.TeamRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.service.RunQueryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-rendered query pages. Each mode (compare, phrase, fuzzy, aggregate)
 * is a GET with the query parameters in the URL, so results are bookmarkable
 * and the browser's back button works.
 *
 * <p>All queries go through {@link RunQueryService}, which is wrapped in
 * {@code SecuredTransactionTemplate} and therefore runs under RLS. The
 * controller never filters by team; the database does.
 */
@Controller
@RequestMapping("/query")
public class QueryController {

    private final RunQueryService runQueryService;
    private final AppUserRepository appUserRepository;
    private final TeamRepository teamRepository;

    public QueryController(RunQueryService runQueryService,
                           AppUserRepository appUserRepository,
                           TeamRepository teamRepository) {
        this.runQueryService = runQueryService;
        this.appUserRepository = appUserRepository;
        this.teamRepository = teamRepository;
    }

    /**
     * Aggregate mode. Returns a grouped or single-value result table.
     *
     * <p>All parameters are optional. When {@code agg} and {@code metric}
     * are absent the page renders the form with no results. When present,
     * the aggregate query runs.
     */
    @GetMapping("/aggregate")
    public String aggregate(
            @RequestParam(required = false) String agg,
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String batch,
            Model model) {

        populateCommonModel(model, "query");

        boolean hasQuery = notBlank(agg) && (notBlank(metric) || "COUNT".equalsIgnoreCase(agg));

        List<Map<String, Object>> results = List.of();
        if (hasQuery) {
            try {
                results = runQueryService.aggregate(agg, metric, groupBy, batch);
            } catch (IllegalArgumentException e) {
                model.addAttribute("queryError", e.getMessage());
            }
        }

        model.addAttribute("agg", agg);
        model.addAttribute("metric", metric);
        model.addAttribute("groupBy", groupBy);
        model.addAttribute("batch", batch);
        model.addAttribute("results", results);
        model.addAttribute("hasQuery", hasQuery);

        return "query/aggregate";
    }

    // ---------------------------------------------------------------------
    // Helpers — shared with PageController once the other modes land
    // ---------------------------------------------------------------------

    /**
     * Populate currentUser and assignedTeams for the shared header fragment.
     * Duplicated from PageController for now; will be factored into a
     * common base class or advice when the second controller needs it.
     */
    private void populateCommonModel(Model model, String active) {
        AppSecurityContext.UserPrincipal principal = AppSecurityContext.require();
        AppUser currentUser = appUserRepository.findById(principal.userId())
                .orElseThrow(() -> new IllegalStateException(
                        "Session identity has no matching app_users row"));

        List<Team> assignedTeams;
        if ("supervisor".equals(currentUser.getAppRole())) {
            assignedTeams = teamRepository.findAssignedTeams(currentUser.getId());
        } else if (currentUser.getTeamId() != null) {
            assignedTeams = teamRepository.findById(currentUser.getTeamId())
                    .map(List::of)
                    .orElse(List.of());
        } else {
            assignedTeams = List.of();
        }

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("assignedTeams", assignedTeams);
        model.addAttribute("active", active);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}