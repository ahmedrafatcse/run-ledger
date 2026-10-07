package com.runledger.controller;

import com.runledger.entity.AppUser;
import com.runledger.entity.Run;
import com.runledger.entity.Team;
import com.runledger.repository.AppUserRepository;
import com.runledger.repository.TeamRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.security.IntegrityStatus;
import com.runledger.security.IntegrityStatusService;
import com.runledger.service.RunQueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

import java.util.Optional;

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
    private final IntegrityStatusService integrityStatusService;

    public PageController(RunQueryService runQueryService,
                          AppUserRepository appUserRepository,
                          TeamRepository teamRepository,
                          IntegrityStatusService integrityStatusService) {
        this.runQueryService = runQueryService;
        this.appUserRepository = appUserRepository;
        this.teamRepository = teamRepository;
        this.integrityStatusService = integrityStatusService;
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

        // Resolve the teams this user can see.
        // A researcher has exactly one; a supervisor has zero-or-more
        // (their assigned teams); an admin would have all — not modeled
        // here because admin has no policy and sees nothing.
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
        model.addAttribute("assignedTeams", assignedTeams);

        return "runs";
    }

    /**
     * Run detail. 404 for both "doesn't exist" and "exists, wrong team" —
     * the RLS SELECT policy filters the row out of the result, so the
     * controller can't distinguish them, and it shouldn't. Confirming a
     * row exists on a team the requester can't see would itself be a leak.
     *
     * <p>The integrity badge is computed only for rows the requester can
     * see. A tamper on a row they can't see is invisible to them by design;
     * a tamper on their own row shows as TAMPERED.
     */
    @GetMapping("/runs/{id}")
    public String detail(@PathVariable Long id,
                         HttpServletResponse response,
                         Model model) {
        Optional<Run> run = runQueryService.getRunById(id);
        if (run.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }

        IntegrityStatus status = integrityStatusService.check(run.get());
        model.addAttribute("run", run.get());
        model.addAttribute("status", status);
        return "run";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}