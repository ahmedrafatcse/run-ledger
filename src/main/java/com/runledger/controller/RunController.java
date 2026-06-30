package com.runledger.controller;

// handles API endpoints (receives req, reads inputs, calls repo/service, returns res)
// MERN equivalent = router + controller

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.RunRequest;
import com.runledger.dto.RunResponse;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// to create location header for res
import java.net.URI;

// take raw json as input, store into db as payload, return saved db row as json
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunRepository runRepository;
    private final ObjectMapper objectMapper; // used to parse stored JSON string into JsonNode

    // Spring automatically injects RunRepository + ObjectMapper (dependency injection)
    public RunController(RunRepository runRepository, ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping // POST method
    public ResponseEntity<RunResponse> ingestRun(@Valid @RequestBody RunRequest request) {

        // Convert request DTO -> entity (entity is what gets saved into DB)
        Run run = new Run();
        run.setPayload(request.payload().toString()); // store JSON as raw string in jsonb column

        Run saved = runRepository.save(run); // save to db

        // Convert saved entity -> response DTO (DTO is what we return to client)
        RunResponse response = new RunResponse(
                saved.getId(),
                request.payload(),      // return actual JSON instead of escaped string
                saved.getCreatedAt()
        );

        return ResponseEntity
                .created(URI.create("/api/runs/" + saved.getId()))
                .body(response);
    }

    @GetMapping("/{id}") // GET method
    public ResponseEntity<RunResponse> getRun(@PathVariable Long id) {

        return runRepository.findById(id)
                .map(run -> {
                    try {
                        // Convert stored payload string back into JSON
                        JsonNode payloadNode = objectMapper.readTree(run.getPayload());

                        RunResponse response = new RunResponse(
                                run.getId(),
                                payloadNode,
                                run.getCreatedAt()
                        );

                        return ResponseEntity.ok(response);

                    } catch (Exception e) {
                        // should not happen unless DB contains corrupted/non-JSON data
                        throw new RuntimeException("Stored payload is not valid JSON", e);
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }
}