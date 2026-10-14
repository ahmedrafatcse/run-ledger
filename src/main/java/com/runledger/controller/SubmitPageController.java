package com.runledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.BatchSummary;
import com.runledger.dto.RunRequest;
import com.runledger.entity.AppUser;
import com.runledger.entity.Run;
import com.runledger.entity.Team;
import com.runledger.repository.AppUserRepository;
import com.runledger.repository.TeamRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.service.RunIngestionService;
import com.runledger.service.RunQueryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Paste-a-JSON-payload form.
 *
 * <p>Researcher-only: {@link RunIngestionService#ingest} rejects non-researcher
 * identities, so the page is scoped to match. Supervisors get a redirect,
 * not a form that always fails.
 *
 * <p>Payload must include {@code _source.file}. Without it, the CLI
 * assigns an anonymous identifier and the row can't be found later. For
 * a UI paste, that's a bad default — reject instead of silently
 * creating an unidentifiable run.
 *
 * <p>Size cap is a UX guard, not a security boundary. The proper limit
 * belongs at the container level (Tomcat's {@code maxPostSize}) when the
 * file picker ships.
 */
@Controller
@RequestMapping("/submit")
public class SubmitPageController {

    /** Paste payload cap. 1 MB is generous for hand-typed JSON. */
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;

    private final RunIngestionService ingestionService;
    private final RunQueryService runQueryService;
    private final AppUserRepository appUserRepository;
    private final TeamRepository teamRepository;
    private final ObjectMapper objectMapper;

    public SubmitPageController(RunIngestionService ingestionService,
                                RunQueryService runQueryService,
                                AppUserRepository appUserRepository,
                                TeamRepository teamRepository,
                                ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.runQueryService = runQueryService;
        this.appUserRepository = appUserRepository;
        this.teamRepository = teamRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String form(Model model, RedirectAttributes ra) {
        if (!isResearcher()) {
            ra.addFlashAttribute("submitError",
                    "Only researchers can submit runs.");
            return "redirect:/";
        }
        populateCommonModel(model, "submit");
        model.addAttribute("batches", runQueryService.listBatches());
        return "submit";
    }

    @PostMapping
    public String submit(@RequestParam(required = false) String payload,
                         @RequestParam(required = false) String batch,
                         Model model,
                         RedirectAttributes ra) {
        if (!isResearcher()) {
            ra.addFlashAttribute("submitError",
                    "Only researchers can submit runs.");
            return "redirect:/";
        }
        try {
            if (payload == null || payload.isBlank()) {
                throw new IllegalArgumentException("Payload is required.");
            }
            if (payload.length() > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException(
                        "Payload exceeds " + (MAX_PAYLOAD_BYTES / 1024)
                                + " KB. Trim or use the CLI for large runs.");
            }

            JsonNode node;
            try {
                node = objectMapper.readTree(payload);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Payload is not valid JSON: " + e.getMessage());
            }

            if (node.path("_source").path("file").asText("").isBlank()) {
                throw new IllegalArgumentException(
                        "Payload must contain \"_source\": {\"file\": \"...\"} "
                                + "so the run can be identified later. "
                                + "Every run the CLI scan produces has this field.");
            }

            String cleanBatch = (batch != null && !batch.isBlank()) ? batch.trim() : null;
            Run saved = ingestionService.ingest(new RunRequest(node, cleanBatch));

            ra.addFlashAttribute("submitSuccess",
                    "Ingested run " + saved.getId() + " (v" + saved.getVersion() + ")");
            return "redirect:/runs/" + saved.getId();
        } catch (Exception e) {
            populateCommonModel(model, "submit");
            model.addAttribute("batches", runQueryService.listBatches());
            model.addAttribute("submitError", e.getMessage());
            model.addAttribute("submitPayload", payload);
            model.addAttribute("submitBatch", batch);
            return "submit";
        }
    }

    // ── helpers ──

    private boolean isResearcher() {
        AppSecurityContext.UserPrincipal p = AppSecurityContext.require();
        return "researcher".equals(p.role()) && p.teamId() != null;
    }

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