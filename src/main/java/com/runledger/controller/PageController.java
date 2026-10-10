package com.runledger.controller;

import com.runledger.dto.PagedView;
import com.runledger.entity.AppUser;
import com.runledger.entity.Run;
import com.runledger.entity.Team;
import com.runledger.dto.MatchDetail;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
public class PageController {

    private static final int PAGE_SIZE = 50;

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

    @GetMapping("/")
    public String home(
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String op,
            @RequestParam(required = false) String value,
            @RequestParam(required = false, defaultValue = "0") int page,
            Model model) {

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

        boolean hasSearch = notBlank(metric) && notBlank(op) && notBlank(value);

        Pageable pageable = PageRequest.of(page, PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Run> runs;
        Map<String, String> currentParams = new LinkedHashMap<>();
        Map<Long, List<MatchDetail>> matchedByRunId = Map.of();

        if (hasSearch) {
            runs = runQueryService.queryByMetric(metric, op, value, pageable);
            String resolvedPath = runQueryService.resolvePath(metric, null);
            matchedByRunId = runQueryService.findMatches(runs.getContent(), resolvedPath, op, value);
            currentParams.put("metric", metric);
            currentParams.put("op", op);
            currentParams.put("value", value);
            model.addAttribute("searchMetric", metric);
            model.addAttribute("searchOp", op);
            model.addAttribute("searchValue", value);
        } else {
            runs = runQueryService.listRuns(null, pageable);
        }

        model.addAttribute("resultsPage", PagedView.of(runs, "/", currentParams));
        model.addAttribute("hasSearch", hasSearch);
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("assignedTeams", assignedTeams);
        model.addAttribute("matchedByRunId", matchedByRunId);
        model.addAttribute("emptyMessage",
                hasSearch ? "No runs matched that search." : "No runs available.");

        return "runs";
    }

    @GetMapping("/runs/{id}")
    public String detail(@PathVariable Long id,
                         HttpServletResponse response,
                         Model model) {
        Optional<Run> run = runQueryService.getRunById(id);
        if (run.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }

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

        IntegrityStatus status = integrityStatusService.check(run.get());
        model.addAttribute("run", run.get());
        model.addAttribute("status", status);
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("assignedTeams", assignedTeams);
        return "run";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}