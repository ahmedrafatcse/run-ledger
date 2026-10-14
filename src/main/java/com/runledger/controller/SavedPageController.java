package com.runledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.entity.AppUser;
import com.runledger.entity.SavedSearch;
import com.runledger.entity.Team;
import com.runledger.repository.AppUserRepository;
import com.runledger.repository.TeamRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.service.SavedSearchService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browser-facing saved-search page.
 *
 * <p>Distinct from {@link SavedSearchController} at {@code /api/saved}:
 * that one is the CLI endpoint (JSON body, no CSRF). This one renders
 * pages and accepts form POSTs (with CSRF), and applies POST-Redirect-GET
 * after every mutation.
 *
 * <p>Owner scoping is enforced by {@link SavedSearchService}: every call
 * resolves the owner from {@link AppSecurityContext} and RLS on
 * {@code saved_search} enforces the same boundary at the database layer.
 * A cross-owner read or delete is invisible, not an error.
 */
@Controller
@RequestMapping("/saved")
public class SavedPageController {

    private final SavedSearchService savedSearchService;
    private final AppUserRepository appUserRepository;
    private final TeamRepository teamRepository;
    private final ObjectMapper objectMapper;

    public SavedPageController(SavedSearchService savedSearchService,
                               AppUserRepository appUserRepository,
                               TeamRepository teamRepository,
                               ObjectMapper objectMapper) {
        this.savedSearchService = savedSearchService;
        this.appUserRepository = appUserRepository;
        this.teamRepository = teamRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String list(Model model) {
        populateCommonModel(model, "saved");

        List<SavedSearch> searches = savedSearchService.list(null);

        Map<Long, String> rerunUrls = new LinkedHashMap<>();
        Map<Long, String> modeLabels = new LinkedHashMap<>();
        for (SavedSearch s : searches) {
            String url = buildRerunUrl(s.getParamsJson());
            if (url != null) {
                rerunUrls.put(s.getId(), url);
            }
            modeLabels.put(s.getId(), extractMode(s.getParamsJson()));
        }

        model.addAttribute("searches", searches);
        model.addAttribute("rerunUrls", rerunUrls);
        model.addAttribute("modeLabels", modeLabels);
        return "saved/list";
    }

    @PostMapping
    public String save(@RequestParam String name,
                       @RequestParam String mode,
                       @RequestParam(required = false) String batch,
                       @RequestParam MultiValueMap<String, String> allParams,
                       RedirectAttributes ra) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("mode", mode);
            if (batch != null && !batch.isBlank()) {
                payload.put("batch", batch);
            }

            switch (mode) {
                case "phrase" -> {
                    putIfPresent(payload, "q", allParams);
                    if (allParams.containsKey("fuzzy")) {
                        payload.put("fuzzy", true);
                    }
                }
                case "aggregate" -> {
                    putIfPresent(payload, "agg", allParams);
                    putIfPresent(payload, "metric", allParams);
                    putIfPresent(payload, "groupBy", allParams);
                    putIfPresent(payload, "team", allParams);
                }
                case "compare" -> {
                    putIfPresent(payload, "combine", allParams);
                    List<String> metrics = allParams.get("metric");
                    List<String> ops = allParams.get("op");
                    List<String> values = allParams.get("value");
                    if (metrics != null && ops != null && values != null) {
                        int n = Math.min(metrics.size(),
                                Math.min(ops.size(), values.size()));
                        List<Map<String, String>> filters = new ArrayList<>();
                        for (int i = 0; i < n; i++) {
                            Map<String, String> f = new LinkedHashMap<>();
                            f.put("metric", metrics.get(i));
                            f.put("op", ops.get(i));
                            f.put("value", values.get(i));
                            filters.add(f);
                        }
                        payload.put("filters", filters);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown mode: " + mode);
            }

            String paramsJson = objectMapper.writeValueAsString(payload);
            savedSearchService.save(name, batch, paramsJson);
            ra.addFlashAttribute("savedMessage", "Saved \"" + name + "\"");
        } catch (Exception e) {
            ra.addFlashAttribute("savedError", "Could not save: " + e.getMessage());
        }
        return "redirect:/saved";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam String name,
                         @RequestParam(required = false) String batch,
                         RedirectAttributes ra) {
        try {
            savedSearchService.delete(name, batch);
            ra.addFlashAttribute("savedMessage", "Deleted \"" + name + "\"");
        } catch (Exception e) {
            ra.addFlashAttribute("savedError", "Could not delete: " + e.getMessage());
        }
        return "redirect:/saved";
    }

    // ── helpers ──

    private static void putIfPresent(Map<String, Object> payload, String key,
                                     MultiValueMap<String, String> params) {
        if (params.containsKey(key)) {
            String v = params.getFirst(key);
            if (v != null && !v.isBlank()) {
                payload.put(key, v);
            }
        }
    }

    private String extractMode(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return "unknown";
        }
        try {
            return objectMapper.readTree(paramsJson).path("mode").asText("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String buildRerunUrl(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(paramsJson);
            String mode = node.path("mode").asText("");
            if (mode.isEmpty()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            String path;
            switch (mode) {
                case "phrase" -> {
                    path = "/query/phrase";
                    addEncoded(parts, "q", node, "q");
                    if (node.path("fuzzy").asBoolean(false)) {
                        parts.add("fuzzy=true");
                    }
                }
                case "aggregate" -> {
                    path = "/query/aggregate";
                    addEncoded(parts, "agg", node, "agg");
                    addEncoded(parts, "metric", node, "metric");
                    addEncoded(parts, "groupBy", node, "groupBy");
                    addEncoded(parts, "team", node, "team");
                }
                case "compare" -> {
                    path = "/query/compare";
                    JsonNode filters = node.path("filters");
                    if (filters.isArray()) {
                        for (JsonNode f : filters) {
                            parts.add("metric=" + enc(f.path("metric").asText("")));
                            parts.add("op=" + enc(f.path("op").asText("")));
                            parts.add("value=" + enc(f.path("value").asText("")));
                        }
                    }
                    addEncoded(parts, "combine", node, "combine");
                }
                default -> {
                    return null;
                }
            }
            addEncoded(parts, "batch", node, "batch");
            return parts.isEmpty() ? path : path + "?" + String.join("&", parts);
        } catch (Exception e) {
            return null;
        }
    }

    private static void addEncoded(List<String> parts, String paramName,
                                   JsonNode node, String fieldName) {
        if (node.hasNonNull(fieldName) && !node.get(fieldName).asText().isBlank()) {
            parts.add(paramName + "=" + enc(node.get(fieldName).asText()));
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
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