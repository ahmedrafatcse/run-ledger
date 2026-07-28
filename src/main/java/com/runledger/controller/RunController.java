package com.runledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.MatchDetail;
import com.runledger.dto.MultiFilterRequest;
import com.runledger.dto.RunRequest;
import com.runledger.dto.RunResponse;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import com.runledger.service.RunIngestionService;
import com.runledger.service.RunQueryService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final RunQueryService runQueryService;
    private final RunIngestionService ingestionService;

    public RunController(RunRepository runRepository,
                         ObjectMapper objectMapper,
                         RunQueryService runQueryService,
                         RunIngestionService ingestionService) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.runQueryService = runQueryService;
        this.ingestionService = ingestionService;
    }

    // ---------- Ingest a new run ----------
    @PostMapping
    public ResponseEntity<RunResponse> ingestRun(@Valid @RequestBody RunRequest request) {
        Run saved = ingestionService.ingest(request);
        // Extract payload JsonNode for response
        JsonNode payload = objectMapper.valueToTree(request.payload());
        return ResponseEntity
                .created(URI.create("/api/runs/" + saved.getId()))
                .body(new RunResponse(saved.getId(), payload, saved.getCreatedAt()));
    }

    // ---------- Retrieve a single run by ID ----------
    @GetMapping("/{id}")
    public ResponseEntity<RunResponse> getRun(@PathVariable Long id) {
        return runRepository.findById(id)
                .map(run -> ResponseEntity.ok(toRunResponse(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ---------- Unified GET: text search, block search, or batch listing ----------
    @GetMapping
    public ResponseEntity<Page<RunResponse>> searchOrList(
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String op,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) String batch,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean fuzzy,
            @RequestParam(required = false) Integer block,
            @RequestParam(required = false, defaultValue = "true") boolean pointers,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        // Full‑text / fuzzy search
        if (q != null && !q.isBlank()) {
            Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
            Page<Run> runs;
            if (fuzzy) {
                double threshold = 0.3;
                runs = (batch != null && !batch.isBlank())
                        ? runQueryService.searchByFuzzy(q, threshold, batch, unsorted)
                        : runQueryService.searchByFuzzy(q, threshold, unsorted);
            } else {
                runs = (batch != null && !batch.isBlank())
                        ? runQueryService.searchByPhrase(q, batch, unsorted)
                        : runQueryService.searchByPhrase(q, unsorted);
            }
            return ResponseEntity.ok(runs.map(this::toRunResponse));
        }

        // Metric block search / batch listing
        boolean hasSearch = metric != null || op != null || value != null;
        boolean hasBatch  = batch != null && !batch.isBlank();

        if (hasSearch) {
            if (metric == null || op == null || value == null) {
                throw new IllegalArgumentException(
                        "Missing required search parameter(s): metric, op, value must all be present.");
            }

            String resolvedPath = hasBatch
                    ? runQueryService.resolvePath(metric, batch)
                    : metric;

            Pageable unsortedPageable = PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize()
            );
            Page<Run> runs = hasBatch
                    ? runQueryService.queryByMetric(metric, op, value, batch, unsortedPageable)
                    : runQueryService.queryByMetric(metric, op, value, unsortedPageable);

            // Build response list with optional block truncation and matched pointers
            List<RunResponse> responses = buildResponses(runs.getContent(), resolvedPath,
                    op, value, block, pointers);
            Page<RunResponse> responsePage = new PageImpl<>(responses, unsortedPageable, runs.getTotalElements());
            return ResponseEntity.ok(responsePage);
        }

        if (hasBatch) {
            Page<Run> runs = runRepository.findByBatch(batch, pageable);
            return ResponseEntity.ok(runs.map(this::toRunResponse));
        }

        Page<Run> runs = runRepository.findAll(pageable);
        return ResponseEntity.ok(runs.map(this::toRunResponse));
    }

    // ---------- Multi‑condition AND/OR search ----------
    @PostMapping("/search")
    public ResponseEntity<Page<RunResponse>> searchMulti(
            @Valid @RequestBody MultiFilterRequest request,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<Run> runs = runQueryService.queryByMultipleFilters(request, unsorted);
        return ResponseEntity.ok(runs.map(this::toRunResponse));
    }

    // ---------- Metric key discovery ----------
    @GetMapping("/metrics")
    public ResponseEntity<?> getMetrics(
            @RequestParam(required = false) String batch,
            @RequestParam(required = false, defaultValue = "false") boolean verbose) {

        if (batch == null || batch.isBlank()) {
            return ResponseEntity.ok(runQueryService.getAvailableMetrics());
        }
        if (!verbose) {
            return ResponseEntity.ok(runQueryService.getAvailableMetrics(batch));
        }
        return ResponseEntity.ok(runQueryService.getAvailableMetricsVerbose(batch));
    }

    // ---------- Helper methods ----------

    /**
     * Builds a list of RunResponses, applying block truncation and matched pointers.
     */
    private List<RunResponse> buildResponses(List<Run> runs, String resolvedPath,
                                             String op, String value,
                                             Integer block, boolean pointers) {
        // Pre‑fetch array matches for all runs if needed (one query for the page)
        Map<Long, List<MatchDetail>> arrayMatchesMap = null;
        boolean isArrayPath = resolvedPath.contains("[]");
        if (pointers && isArrayPath) {
            List<Long> ids = runs.stream().map(Run::getId).toList();
            int arrayIdx = resolvedPath.indexOf("[]");
            String arrayPart = resolvedPath.substring(0, arrayIdx);
            String leafPart = resolvedPath.substring(arrayIdx + 2);
            if (leafPart.startsWith(".")) leafPart = leafPart.substring(1);
            String leafParts = leafPart.replace(".", ",");
            arrayMatchesMap = runQueryService.getArrayMatches(ids, arrayPart, leafParts, op, value);
        }

        List<RunResponse> responses = new ArrayList<>();
        for (Run run : runs) {
            RunResponse resp;
            if (block != null && block >= 0) {
                resp = toRunResponse(run, resolvedPath, block);
            } else {
                resp = toRunResponse(run);
            }

            // Always add matched when pointers is true (no block restriction)
            if (pointers) {
                List<MatchDetail> matched;
                if (isArrayPath) {
                    matched = arrayMatchesMap != null
                            ? arrayMatchesMap.getOrDefault(run.getId(), List.of())
                            : List.of();
                } else {
                    matched = runQueryService.getScalarMatch(run, resolvedPath, op, value);
                }
                resp.setMatched(matched.isEmpty() ? null : matched);
            }
            responses.add(resp);
        }
        return responses;
    }

    // ---------- Response builders ----------

    private RunResponse toRunResponse(Run run) {
        try {
            JsonNode payloadNode = objectMapper.readTree(run.getPayload());
            return new RunResponse(run.getId(), payloadNode, run.getCreatedAt());
        } catch (Exception e) {
            throw new RuntimeException("Stored payload is not valid JSON", e);
        }
    }

    private RunResponse toRunResponse(Run run, String dotPath, int block) {
        try {
            JsonNode fullPayload = objectMapper.readTree(run.getPayload());
            JsonNode truncated = extractAncestor(fullPayload, dotPath, block);
            return new RunResponse(run.getId(), truncated, run.getCreatedAt());
        } catch (Exception e) {
            throw new RuntimeException("Stored payload is not valid JSON", e);
        }
    }

    // ---------- Block‑depth extraction ----------

    private JsonNode extractAncestor(JsonNode root, String dotPath, int block) {
        if (dotPath == null || dotPath.isBlank() || block < 0) {
            return root;
        }
        if (dotPath.contains("[]")) {
            return extractAncestorForArray(root, dotPath, block);
        }
        // scalar logic
        String[] parts = dotPath.split("\\.");
        int ancestorPartsCount = parts.length - (block + 1);
        if (ancestorPartsCount <= 0) {
            return root;
        }
        StringBuilder pointer = new StringBuilder();
        for (int i = 0; i < ancestorPartsCount; i++) {
            pointer.append("/").append(parts[i]);
        }
        JsonNode ancestor = root.at(pointer.toString());
        return ancestor.isMissingNode() ? root : ancestor;
    }

    private JsonNode extractAncestorForArray(JsonNode root, String dotPath, int block) {
        String[] parts = dotPath.split("\\.");
        int arrayIndex = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].contains("[]")) {
                arrayIndex = i;
                break;
            }
        }
        if (arrayIndex == -1) return root;
        StringBuilder pointer = new StringBuilder();
        for (int i = 0; i <= arrayIndex; i++) {
            String clean = parts[i].replace("[]", "");
            pointer.append("/").append(clean);
        }
        JsonNode arrayNode = root.at(pointer.toString());
        if (arrayNode.isMissingNode()) return root;
        if (block == 0) return arrayNode;

        int arrayDepth = arrayIndex + 1;
        int ancestorPartsCount = arrayDepth - block;
        if (ancestorPartsCount <= 0) return root;
        pointer.setLength(0);
        for (int i = 0; i < ancestorPartsCount; i++) {
            String clean = parts[i].replace("[]", "");
            pointer.append("/").append(clean);
        }
        JsonNode ancestor = root.at(pointer.toString());
        return ancestor.isMissingNode() ? root : ancestor;
    }
}