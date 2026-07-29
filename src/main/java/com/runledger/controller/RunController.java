package com.runledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.Filter;
import com.runledger.dto.MatchDetail;
import com.runledger.dto.MultiFilterRequest;
import com.runledger.dto.RunRequest;
import com.runledger.dto.RunResponse;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import com.runledger.service.ResolvedFilter;
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
import java.util.stream.Collectors;

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
        JsonNode payload = request.payload();
        return ResponseEntity
                .created(URI.create("/api/runs/" + saved.getId()))
                .body(new RunResponse(saved.getId(), payload, saved.getCreatedAt()));
    }

    // ---------- Aggregate endpoint ----------
    @GetMapping("/aggregate")
    public ResponseEntity<?> aggregate(
            @RequestParam String agg,
            @RequestParam String metric,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String batch) {
        try {
            List<Map<String, Object>> results = runQueryService.aggregate(agg, metric, groupBy, batch);
            return ResponseEntity.ok(results);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ---------- Retrieve a single run by ID ----------
    @GetMapping("/{id}")
    public ResponseEntity<RunResponse> getRun(@PathVariable Long id) {
        return runRepository.findById(id)
                .map(run -> ResponseEntity.ok(toRunResponse(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/versions")
    public ResponseEntity<List<RunResponse>> getVersions(
            @RequestParam String batch,
            @RequestParam String sourceFile,
            @RequestParam int sourceIndex) {
        List<Run> runs = runRepository.findByBatchAndSourceFileAndSourceIndexOrderByVersionAsc(
                batch, sourceFile, sourceIndex);
        return ResponseEntity.ok(runs.stream().map(this::toRunResponse).toList());
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
            List<RunResponse> responses = buildResponsesForSingleMetric(
                    runs.getContent(), resolvedPath, op, value, block, pointers);
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

        // Resolve paths and extract common array path for compound pointers / block-depth
        List<ResolvedFilter> resolvedFilters = request.filters().stream()
                .map(f -> new ResolvedFilter(
                        request.batch() != null && !request.batch().isBlank()
                                ? runQueryService.resolvePath(f.metric(), request.batch())
                                : f.metric(),
                        f.op(), f.value()))
                .collect(Collectors.toList());

        boolean pointers = request.pointers() != null ? request.pointers() : true;

        // Check if there are any array conditions at all
        boolean hasArrayConditions = resolvedFilters.stream().anyMatch(f -> f.getPath().contains("[]"));

        if (pointers && !hasArrayConditions) {
            // All conditions are scalar – build a MatchDetail for each filter
            List<RunResponse> responses = runs.getContent().stream()
                    .map(run -> buildScalarCompoundResponse(run, resolvedFilters, request.block()))
                    .collect(Collectors.toList());
            Page<RunResponse> responsePage = new PageImpl<>(responses, unsorted, runs.getTotalElements());
            return ResponseEntity.ok(responsePage);
        }

        // Determine if all array conditions reference the same array root
        String commonArrayRoot = findCommonArrayRoot(resolvedFilters);
        boolean allArraySameRoot = commonArrayRoot != null;

        // For block-depth, use the first resolved path if all metrics agree on a common path
        String commonPath = findCommonBlockPath(resolvedFilters);

        List<RunResponse> responses;
        if (pointers && allArraySameRoot) {
            // Compound pointers on same array
            responses = buildResponsesForCompoundArray(
                    runs.getContent(), resolvedFilters, commonArrayRoot, request.combine(),
                    request.block(), pointers, commonPath);
        } else {
            // No pointers or different arrays – simple mapping with optional block
            responses = runs.getContent().stream()
                    .map(run -> {
                        if (request.block() != null && request.block() >= 0 && commonPath != null) {
                            return toRunResponse(run, commonPath, request.block());
                        } else {
                            return toRunResponse(run);
                        }
                    })
                    .collect(Collectors.toList());
        }

        Page<RunResponse> responsePage = new PageImpl<>(responses, unsorted, runs.getTotalElements());
        return ResponseEntity.ok(responsePage);
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

    /** Single-metric response builder – unchanged from before */
    private List<RunResponse> buildResponsesForSingleMetric(List<Run> runs, String resolvedPath,
                                                            String op, String value,
                                                            Integer block, boolean pointers) {
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

    /** Compound array pointer extraction */
    private List<RunResponse> buildResponsesForCompoundArray(
            List<Run> runs, List<ResolvedFilter> filters, String commonArrayRoot,
            String combine, Integer block, boolean pointers, String blockPath) {

        List<Long> ids = runs.stream().map(Run::getId).toList();
        // Delegate to RunQueryService for compound array matches
        Map<Long, List<MatchDetail>> matchesMap = runQueryService.getCompoundArrayMatches(
                ids, commonArrayRoot, filters, combine);

        List<RunResponse> responses = new ArrayList<>();
        for (Run run : runs) {
            RunResponse resp;
            if (block != null && block >= 0 && blockPath != null) {
                resp = toRunResponse(run, blockPath, block);
            } else {
                resp = toRunResponse(run);
            }
            if (pointers) {
                List<MatchDetail> matched = matchesMap.getOrDefault(run.getId(), List.of());
                resp.setMatched(matched.isEmpty() ? null : matched);
            }
            responses.add(resp);
        }
        return responses;
    }

    /**
     * Build a RunResponse with scalar compound pointers.
     * For AND, every filter must hold; for OR, we re-check each one.
     */
    private RunResponse buildScalarCompoundResponse(Run run, List<ResolvedFilter> filters, Integer block) {
        RunResponse resp;
        if (block != null && block >= 0 && !filters.isEmpty()) {
            // Use the first filter's path as the block anchor (all scalar paths are valid)
            resp = toRunResponse(run, filters.get(0).getPath(), block);
        } else {
            resp = toRunResponse(run);
        }

        List<MatchDetail> matched = new ArrayList<>();
        for (ResolvedFilter f : filters) {
            // Re-check the condition against the payload
            List<MatchDetail> single = runQueryService.getScalarMatch(run, f.getPath(), f.getOp(), f.getValue());
            if (!single.isEmpty()) {
                matched.add(single.get(0));
            }
        }
        resp.setMatched(matched.isEmpty() ? null : matched);
        return resp;
    }

    /** Finds the common array root (part before []) if all array filters share it, else null */
    private String findCommonArrayRoot(List<ResolvedFilter> filters) {
        String common = null;
        for (ResolvedFilter f : filters) {
            if (f.getPath().contains("[]")) {
                int idx = f.getPath().indexOf("[]");
                String root = f.getPath().substring(0, idx);
                if (common == null) {
                    common = root;
                } else if (!common.equals(root)) {
                    return null;
                }
            }
        }
        return common;
    }

    /** Returns a common dot‑path for block‑depth if all filters agree on the path (for non‑array metrics) */
    private String findCommonBlockPath(List<ResolvedFilter> filters) {
        String common = null;
        for (ResolvedFilter f : filters) {
            String path = f.getPath();
            if (path.contains("[]")) {
                if (common == null) {
                    common = path;
                } else if (!common.equals(path)) {
                    return null;
                }
            } else {
                if (common == null) {
                    common = path;
                } else if (!common.equals(path)) {
                    return null;
                }
            }
        }
        return common;
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