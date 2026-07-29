package com.runledger.controller;

import com.runledger.entity.SavedSearch;
import com.runledger.service.SavedSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/saved")
public class SavedSearchController {

    private final SavedSearchService savedSearchService;

    public SavedSearchController(SavedSearchService savedSearchService) {
        this.savedSearchService = savedSearchService;
    }

    @PostMapping
    public ResponseEntity<SavedSearch> save(@RequestBody SaveRequest request) {
        SavedSearch saved = savedSearchService.save(request.name(), request.batch(), request.paramsJson());
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public ResponseEntity<List<SavedSearch>> list(@RequestParam(required = false) String batch) {
        return ResponseEntity.ok(savedSearchService.list(batch));
    }

    @GetMapping("/{name}")
    public ResponseEntity<SavedSearch> get(@PathVariable String name, @RequestParam(required = false) String batch) {
        SavedSearch ss = savedSearchService.get(name, batch);
        return ss != null ? ResponseEntity.ok(ss) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name, @RequestParam(required = false) String batch) {
        savedSearchService.delete(name, batch);
        return ResponseEntity.noContent().build();
    }

    public record SaveRequest(String name, String batch, String paramsJson) {}
}