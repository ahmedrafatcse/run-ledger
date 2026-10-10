package com.runledger.controller;

import com.runledger.dto.FieldDiff;
import com.runledger.entity.AppUser;
import com.runledger.entity.Run;
import com.runledger.entity.Team;
import com.runledger.repository.AppUserRepository;
import com.runledger.repository.TeamRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.security.IntegrityStatus;
import com.runledger.security.IntegrityStatusService;
import com.runledger.service.PayloadFlattener;
import com.runledger.service.RunQueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Version history and diff pages for a run identity.
 *
 * <p>Both routes are gated by the same RLS that filters every other page:
 * {@code getRunById} returns empty for a row the identity can't see, so
 * cross-team access produces a 404 here as it does on the detail page.
 * A second-level check verifies that the two runs being compared belong
 * to the same identity; comparing across identities is not meaningful
 * and the second run's row is filtered independently.
 */
@Controller
public class RunHistoryController {

    private final RunQueryService runQueryService;
    private final AppUserRepository appUserRepository;
    private final TeamRepository teamRepository;
    private final IntegrityStatusService integrityStatusService;
    private final PayloadFlattener payloadFlattener;

    public RunHistoryController(RunQueryService runQueryService,
                                AppUserRepository appUserRepository,
                                TeamRepository teamRepository,
                                IntegrityStatusService integrityStatusService,
                                PayloadFlattener payloadFlattener) {
        this.runQueryService = runQueryService;
        this.appUserRepository = appUserRepository;
        this.teamRepository = teamRepository;
        this.integrityStatusService = integrityStatusService;
        this.payloadFlattener = payloadFlattener;
    }

    // -----------------------------------------------------------------
    // History
    // -----------------------------------------------------------------

    @GetMapping("/runs/{id}/history")
    public String history(@PathVariable Long id,
                          HttpServletResponse response,
                          Model model) {
        Optional<Run> runOpt = runQueryService.getRunById(id);
        if (runOpt.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }

        Run run = runOpt.get();
        List<Run> versions = runQueryService.getVersions(
                run.getBatch(), run.getSourceFile(), run.getSourceIndex());

        Map<Long, IntegrityStatus> statuses = new LinkedHashMap<>();
        for (Run v : versions) {
            statuses.put(v.getId(), integrityStatusService.check(v));
        }

        populateCommonModel(model, "runs");
        model.addAttribute("run", run);
        model.addAttribute("versions", versions);
        model.addAttribute("statuses", statuses);
        return "history";
    }

    // -----------------------------------------------------------------
    // Diff
    // -----------------------------------------------------------------

    @GetMapping("/runs/compare")
    public String compare(@RequestParam Long id1,
                          @RequestParam Long id2,
                          HttpServletResponse response,
                          Model model) {
        Optional<Run> run1Opt = runQueryService.getRunById(id1);
        Optional<Run> run2Opt = runQueryService.getRunById(id2);

        if (run1Opt.isEmpty() || run2Opt.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }

        Run run1 = run1Opt.get();
        Run run2 = run2Opt.get();

        // Two rows are only meaningfully comparable if they represent the
        // same logical run identity (same batch, source file, source index).
        // Comparing across identities would produce a diff of unrelated
        // payloads, which is never what the user wants.
        boolean sameIdentity =
                java.util.Objects.equals(run1.getBatch(), run2.getBatch())
                        && java.util.Objects.equals(run1.getSourceFile(), run2.getSourceFile())
                        && run1.getSourceIndex() == run2.getSourceIndex();

        if (!sameIdentity) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            model.addAttribute("message", "These runs are not versions of the same submission and cannot be compared.");
            return "error/400";
        }

        List<FieldDiff> diffs = payloadFlattener.diff(run1.getPayload(), run2.getPayload());

        populateCommonModel(model, "runs");
        model.addAttribute("run1", run1);
        model.addAttribute("run2", run2);
        model.addAttribute("diffs", diffs);
        return "diff";
    }

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
}