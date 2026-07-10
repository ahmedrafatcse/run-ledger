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

    // ---------- Block search (parameterised query) with pagination ----------
    // Default: all matching runs (size = Integer.MAX_VALUE), sorted newest first.
    @GetMapping
    public ResponseEntity<Page<RunResponse>> searchRuns(
            @Valid RunSearchRequest searchRequest,
            @PageableDefault(size = Integer.MAX_VALUE, sort = "created_at", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<Run> runs = runQueryService.queryByMetric(
                searchRequest.metric(),
                searchRequest.op(),
                searchRequest.value(),
                pageable
        );

        Page<RunResponse> responses = runs.map(this::toRunResponse);
        return ResponseEntity.ok(responses);
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