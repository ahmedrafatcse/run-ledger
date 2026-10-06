package com.runledger.controller;

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
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered HTML pages. Reads the current identity from
 * {@link AppSecurityContext}, which {@code SessionIdentityFilter} populates
 * for every page request.
 *
 * <p>All queries go through {@link RunQueryService}, which is wrapped in
 * {@code SecuredTransactionTemplate} and therefore runs under RLS. The
 * controller does not filter by team or user; the database does. Alice and
 * Bob hitting the same endpoint see different rows because the RLS SELECT
 * policy on {@code run} resolves their membership from {@code app_users}
 * against the session's {@code app.current_user_id}.
 */
@Controller
public class PageController {

    /** Cap for the home page. No pagination controls; a batch of runs that
     *  exceeds this is a signal to add paging, not a signal to render more. */
    private static final int PAGE_SIZE = 100;

    private final RunQueryService runQueryService;
    private final AppUserRepository appUserRepository;
    private final TeamRepository teamRepository;

    public PageController(RunQueryService runQueryService,
                          AppUserRepository appUserRepository,
                          TeamRepository teamRepository) {
        this.runQueryService = runQueryService;
        this.appUserRepository = appUserRepository;
        this.teamRepository = teamRepository;
    }

    /**
     * Home page: run list with an optional metric search.
     *
     * <p>Search parameters are optional. If all three are present, the page
     * routes to {@link RunQueryService#queryByMetric}; otherwise it lists
     * all visible runs. Both paths go through the wrapped service, so both
     * are subject to RLS.
     */
    @GetMapping("/")
    public String home(
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String op,
            @RequestParam(required = false) String value,
            Model model) {

        AppSecurityContext.UserPrincipal principal = AppSecurityContext.require();

        AppUser currentUser = appUserRepository.findById(principal.userId())
                .orElseThrow(() -> new IllegalStateException(
                        "Session identity has no matching app_users row"));

        String teamName = currentUser.getTeamId() == null
                ? "—"
                : teamRepository.findById(currentUser.getTeamId())
                .map(Team::getName)
                .orElse("—");

        boolean hasSearch = notBlank(metric) && notBlank(op) && notBlank(value);

        Page<Run> runs;
        if (hasSearch) {
            Pageable unsorted = PageRequest.of(0, PAGE_SIZE);
            runs = runQueryService.queryByMetric(metric, op, value, unsorted);
            model.addAttribute("searchMetric", metric);
            model.addAttribute("searchOp", op);
            model.addAttribute("searchValue", value);
        } else {
            Pageable sorted = PageRequest.of(0, PAGE_SIZE,
                    Sort.by(Sort.Direction.DESC, "createdAt"));
            runs = runQueryService.listRuns(null, sorted);
        }

        model.addAttribute("runs", runs.getContent());
        model.addAttribute("totalElements", runs.getTotalElements());
        model.addAttribute("hasSearch", hasSearch);
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("currentTeamName", teamName);

        return "runs";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}