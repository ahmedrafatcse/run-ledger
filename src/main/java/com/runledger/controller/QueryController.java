package com.runledger.controller;

import com.runledger.dto.Filter;
import com.runledger.dto.MultiFilterRequest;
import com.runledger.dto.PagedView;
import com.runledger.entity.AppUser;
import com.runledger.entity.Run;
import com.runledger.entity.Team;
import com.runledger.repository.AppUserRepository;
import com.runledger.repository.TeamRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.service.RunQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/query")
public class QueryController {

    private static final int PAGE_SIZE = 50;

    /** Default fuzzy threshold, matching the CLI's default. */
    private static final double FUZZY_THRESHOLD = 0.3;

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

    // -----------------------------------------------------------------
    // Aggregate
    // -----------------------------------------------------------------

    @GetMapping("/aggregate")
    public String aggregate(
            @RequestParam(required = false) String agg,
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String batch,
            Model model) {

        populateCommonModel(model, "aggregate");

        boolean hasQuery = notBlank(agg)
                && (notBlank(metric) || "COUNT".equalsIgnoreCase(agg));

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

    // -----------------------------------------------------------------
    // Phrase / fuzzy
    // -----------------------------------------------------------------

    @GetMapping("/phrase")
    public String phrase(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean fuzzy,
            @RequestParam(required = false) String batch,
            @RequestParam(required = false, defaultValue = "0") int page,
            Model model) {

        populateCommonModel(model, "phrase");

        boolean hasQuery = notBlank(q);
        boolean hasBatch = notBlank(batch);

        Pageable pageable = PageRequest.of(page, PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Run> runs = Page.empty(pageable);
        Map<String, String> currentParams = new LinkedHashMap<>();

        if (hasQuery) {
            if (fuzzy) {
                runs = hasBatch
                        ? runQueryService.searchByFuzzy(q, FUZZY_THRESHOLD, batch, pageable)
                        : runQueryService.searchByFuzzy(q, FUZZY_THRESHOLD, pageable);
            } else {
                runs = hasBatch
                        ? runQueryService.searchByPhrase(q, batch, pageable)
                        : runQueryService.searchByPhrase(q, pageable);
            }
            currentParams.put("q", q);
            if (fuzzy) currentParams.put("fuzzy", "true");
            if (hasBatch) currentParams.put("batch", batch);
        }

        model.addAttribute("q", q);
        model.addAttribute("fuzzy", fuzzy);
        model.addAttribute("batch", batch);
        model.addAttribute("hasQuery", hasQuery);
        model.addAttribute("resultsPage", PagedView.of(runs, "/query/phrase", currentParams));
        model.addAttribute("emptyMessage",
                fuzzy ? "No runs matched that fuzzy term."
                        : "No runs matched that phrase.");

        return "query/phrase";
    }

    // -----------------------------------------------------------------
    // Compare (single or compound — same operation, N conditions)
    // -----------------------------------------------------------------

    /**
     * Structured search over one or more conditions. The form binds the
     * repeated fields {@code metric}, {@code op}, {@code value} into
     * parallel lists; Spring preserves the order they appear in the DOM,
     * so index {@code i} refers to the same condition across all three.
     *
     * <p>Incomplete rows (any of the three fields blank) are silently
     * skipped. This lets the "Add condition" button append an empty row
     * without requiring the user to delete it before submitting.
     *
     * <p>Not paginated: compound query parameters can't be round-tripped
     * through a simple URL, and the demo data is small enough that one
     * page suffices. If this becomes a limitation, the fix is to switch
     * to a POST form and cache the filter set server-side per session.
     */
    @GetMapping("/compare")
    public String compare(
            @RequestParam(required = false) List<String> metric,
            @RequestParam(required = false) List<String> op,
            @RequestParam(required = false) List<String> value,
            @RequestParam(required = false, defaultValue = "and") String combine,
            @RequestParam(required = false) String batch,
            Model model) {

        populateCommonModel(model, "compare");

        List<Filter> filters = new ArrayList<>();
        if (metric != null && op != null && value != null) {
            int n = Math.min(metric.size(), Math.min(op.size(), value.size()));
            for (int i = 0; i < n; i++) {
                if (notBlank(metric.get(i))
                        && notBlank(op.get(i))
                        && notBlank(value.get(i))) {
                    filters.add(new Filter(metric.get(i), op.get(i), value.get(i)));
                }
            }
        }

        boolean hasQuery = !filters.isEmpty();

        Page<Run> runs = Page.empty();
        if (hasQuery) {
            MultiFilterRequest request = new MultiFilterRequest(
                    filters, combine, batch, null, false);
            runs = runQueryService.queryByMultipleFilters(request, Pageable.unpaged());
        }

        // Always render at least one row: if no filters, an empty template row.
        List<Filter> displayFilters = hasQuery
                ? filters
                : List.of(new Filter("", "gt", ""));

        model.addAttribute("filters", displayFilters);
        model.addAttribute("combine", combine);
        model.addAttribute("batch", batch);
        model.addAttribute("hasQuery", hasQuery);
        model.addAttribute("resultsPage", PagedView.of(runs, "/query/compare", Map.of()));
        model.addAttribute("emptyMessage", "No runs matched those conditions.");

        return "query/compare";
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

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