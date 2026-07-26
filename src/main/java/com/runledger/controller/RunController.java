package com.runledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.MultiFilterRequest;
import com.runledger.dto.RunRequest;
import com.runledger.dto.RunResponse;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import com.runledger.service.RunQueryService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
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

    public RunController(RunRepository runRepository,
                         ObjectMapper objectMapper,
                         RunQueryService runQueryService) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.runQueryService = runQueryService;
    }

    // ---------- Ingest a new run ----------
    @PostMapping
    public ResponseEntity<RunResponse> ingestRun(@Valid @RequestBody RunRequest request) {
        Run run = new Run();
        run.setPayload(request.payload().toString());
        if (request.batch() != null && !request.batch().isBlank()) {
            run.setBatch(request.batch());
        }
        Run saved = runRepository.save(run);
        return ResponseEntity
                .created(URI.create("/api/runs/" + saved.getId()))
                .body(new RunResponse(saved.getId(), request.payload(), saved.getCreatedAt()));
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

            if (block != null && block >= 0) {
                final String path = resolvedPath;
                Page<RunResponse> responses = runs.map(r -> toRunResponse(r, path, block));
                return ResponseEntity.ok(responses);
            } else {
                return ResponseEntity.ok(runs.map(this::toRunResponse));
            }
        }

        if (hasBatch) {
            Page<Run> runs = runRepository.findByBatch(batch, pageable);
            return ResponseEntity.ok(runs.map(this::toRunResponse));
        }

        Page<Run> runs = runRepository.findAll(pageable);
        return ResponseEntity.ok(runs.map(this::toRunResponse));
    }

    // ---------- NEW: Multi‑condition AND/OR search ----------
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

    // ---------- Helper: full payload ----------
    private RunResponse toRunResponse(Run run) {
        try {
            JsonNode payloadNode = objectMapper.readTree(run.getPayload());
            return new RunResponse(run.getId(), payloadNode, run.getCreatedAt());
        } catch (Exception e) {
            throw new RuntimeException("Stored payload is not valid JSON", e);
        }
    }

    // ---------- Helper: truncated payload by block depth ----------
    private RunResponse toRunResponse(Run run, String dotPath, int block) {
        try {
            JsonNode fullPayload = objectMapper.readTree(run.getPayload());
            JsonNode truncated = extractAncestor(fullPayload, dotPath, block);
            return new RunResponse(run.getId(), truncated, run.getCreatedAt());
        } catch (Exception e) {
            throw new RuntimeException("Stored payload is not valid JSON", e);
        }
    }

    private JsonNode extractAncestor(JsonNode root, String dotPath, int block) {
        if (dotPath == null || dotPath.isBlank() || block < 0) {
            return root;
        }
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
}