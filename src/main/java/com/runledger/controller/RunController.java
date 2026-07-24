package com.runledger.controller;

// handles all run functions

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

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

    // ---------- Unified GET: block search or batch listing ----------
    @GetMapping
    public ResponseEntity<Page<RunResponse>> searchOrList(
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String op,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) String batch,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        boolean hasSearch = metric != null || op != null || value != null;
        boolean hasBatch  = batch != null && !batch.isBlank();

        // --- Block search path (any search param present) ---
        if (hasSearch) {
            if (metric == null || op == null || value == null) {
                throw new IllegalArgumentException(
                        "Missing required search parameter(s): metric, op, value must all be present.");
            }
            // Unsorted Pageable so the native query's ORDER BY is used exclusively
            Pageable unsortedPageable = PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize()
            );
            Page<Run> runs = (hasBatch)
                    ? runQueryService.queryByMetric(metric, op, value, batch, unsortedPageable)
                    : runQueryService.queryByMetric(metric, op, value, unsortedPageable);
            return ResponseEntity.ok(runs.map(this::toRunResponse));
        }

        // --- Batch listing (only batch, no search params) ---
        if (hasBatch) {
            Page<Run> runs = runRepository.findByBatch(batch, pageable);
            return ResponseEntity.ok(runs.map(this::toRunResponse));
        }

        // --- No batch, no search → all runs ---
        Page<Run> runs = runRepository.findAll(pageable);
        return ResponseEntity.ok(runs.map(this::toRunResponse));
    }

    // ---------- Metric key discovery ----------
    @GetMapping("/metrics")
    public ResponseEntity<List<String>> getMetrics(@RequestParam(required = false) String batch) {
        List<String> keys = (batch == null || batch.isBlank())
                ? runQueryService.getAvailableMetrics()
                : runQueryService.getAvailableMetrics(batch);
        return ResponseEntity.ok(keys);
    }

    // ---------- Helper ----------
    private RunResponse toRunResponse(Run run) {
        try {
            JsonNode payloadNode = objectMapper.readTree(run.getPayload());
            return new RunResponse(run.getId(), payloadNode, run.getCreatedAt());
        } catch (Exception e) {
            throw new RuntimeException("Stored payload is not valid JSON", e);
        }
    }
}