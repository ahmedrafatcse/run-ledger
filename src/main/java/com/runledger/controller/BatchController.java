package com.runledger.controller;

import com.runledger.dto.BatchSummary;
import com.runledger.service.RunQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * List batches visible to the current identity.
 *
 * <p>The list is derived from {@code run} rows under RLS, so a researcher
 * sees only their own team's batches and a supervisor sees the batches of
 * every team they are assigned to. No filter is applied by the controller;
 * the database does it.
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final RunQueryService runQueryService;

    public BatchController(RunQueryService runQueryService) {
        this.runQueryService = runQueryService;
    }

    @GetMapping
    public ResponseEntity<List<BatchSummary>> list() {
        return ResponseEntity.ok(runQueryService.listBatches());
    }
}