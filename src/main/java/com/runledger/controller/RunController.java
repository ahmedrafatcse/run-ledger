package com.runledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.RunRequest;
import com.runledger.dto.RunResponse;
import com.runledger.dto.RunSearchRequest;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import com.runledger.service.RunQueryService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
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
        // Set batch if provided
        if (request.batch() != null && !request.batch().isBlank()) {
            run.setBatch(request.batch());
        }

        Run saved = runRepository.save(run);

        RunResponse response = new RunResponse(
                saved.getId(),
                request.payload(),               // return original inline JSON
                saved.getCreatedAt()
        );

        return ResponseEntity
                .created(URI.create("/api/runs/" + saved.getId()))
                .body(response);
    }

    // ---------- Retrieve a single run by ID ----------
    @GetMapping("/{id}")
    public ResponseEntity<RunResponse> getRun(@PathVariable Long id) {
        return runRepository.findById(id)
                .map(run -> ResponseEntity.ok(toRunResponse(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ---------- Block search (parameterised query) with pagination and optional batch ----------
    @GetMapping
    public ResponseEntity<Page<RunResponse>> searchRuns(
            @Valid RunSearchRequest searchRequest,
            @PageableDefault(size = Integer.MAX_VALUE, sort = "created_at", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String batch) {

        Page<Run> runs = (batch == null || batch.isBlank())
                ? runQueryService.queryByMetric(searchRequest.metric(), searchRequest.op(), searchRequest.value(), pageable)
                : runQueryService.queryByMetric(searchRequest.metric(), searchRequest.op(), searchRequest.value(), batch, pageable);

        Page<RunResponse> responses = runs.map(this::toRunResponse);
        return ResponseEntity.ok(responses);
    }

    // ---------- Metric key discovery ----------
    @GetMapping("/metrics")
    public ResponseEntity<List<String>> getMetrics(@RequestParam(required = false) String batch) {
        List<String> keys = (batch == null || batch.isBlank())
                ? runQueryService.getAvailableMetrics()
                : runQueryService.getAvailableMetrics(batch);
        return ResponseEntity.ok(keys);
    }

    // ---------- Helper: convert entity to DTO ----------
    private RunResponse toRunResponse(Run run) {
        try {
            JsonNode payloadNode = objectMapper.readTree(run.getPayload());
            return new RunResponse(run.getId(), payloadNode, run.getCreatedAt());
        } catch (Exception e) {
            throw new RuntimeException("Stored payload is not valid JSON", e);
        }
    }
}