package com.runledger.controller;

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

    // ---------- Unified GET: text search, block search, or batch listing ----------
    @GetMapping
    public ResponseEntity<Page<RunResponse>> searchOrList(
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String op,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) String batch,
            @RequestParam(required = false) String q,          // full‑text or fuzzy search term
            @RequestParam(required = false, defaultValue = "false") boolean fuzzy,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        // ------------------------------------------------------------
        // Path A – Full‑text / fuzzy search (only when q is present)
        // ------------------------------------------------------------
        if (q != null && !q.isBlank()) {
            Page<Run> runs;
            // Use an unsorted Pageable – the native query uses its own ranking (or no ordering)
            Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
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

        // ------------------------------------------------------------
        // Path B – Existing block‑search / batch‑listing logic
        // ------------------------------------------------------------
        boolean hasSearch = metric != null || op != null || value != null;
        boolean hasBatch  = batch != null && !batch.isBlank();

        if (hasSearch) {
            if (metric == null || op == null || value == null) {
                throw new IllegalArgumentException(
                        "Missing required search parameter(s): metric, op, value must all be present.");
            }
            Pageable unsortedPageable = PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize()
            );
            Page<Run> runs = hasBatch
                    ? runQueryService.queryByMetric(metric, op, value, batch, unsortedPageable)
                    : runQueryService.queryByMetric(metric, op, value, unsortedPageable);
            return ResponseEntity.ok(runs.map(this::toRunResponse));
        }

        if (hasBatch) {
            Page<Run> runs = runRepository.findByBatch(batch, pageable);
            return ResponseEntity.ok(runs.map(this::toRunResponse));
        }

        // No search params, no batch → return all runs
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