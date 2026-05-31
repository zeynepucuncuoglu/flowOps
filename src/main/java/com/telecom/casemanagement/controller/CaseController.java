package com.telecom.casemanagement.controller;

import com.telecom.casemanagement.dto.*;
import com.telecom.casemanagement.service.CaseService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
@Timed(value = "http.requests", extraTags = {"controller", "CaseController"})
public class CaseController {

    private final CaseService caseService;

    // ── CREATE ────────────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CaseResponse createCase(@Valid @RequestBody CreateCaseRequest req,
                                    @AuthenticationPrincipal UserDetails user) {
        return caseService.createCase(req, user.getUsername());
    }

    // ── READ ──────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public CaseResponse getCase(@PathVariable UUID id) {
        return caseService.getCase(id);
    }

    @GetMapping
    public Page<CaseResponse> searchCases(CaseSearchRequest req) {
        return caseService.searchCases(req);
    }

    // ── WORKFLOW TRANSITION ───────────────────────────────────────────────────

    /**
     * Core PEGA-style "flow action" endpoint.
     * The rule engine validates and applies the transition atomically.
     *
     * POST /api/v1/cases/{id}/transitions
     * Body: { "targetStatus": "IN_REVIEW", "reason": "Initial triage complete" }
     */
    @PostMapping("/{id}/transitions")
    public CaseResponse transition(@PathVariable UUID id,
                                    @Valid @RequestBody TransitionRequest req,
                                    @AuthenticationPrincipal UserDetails user) {
        return caseService.transition(id, req, user.getUsername());
    }

    // ── ASSIGNMENT ────────────────────────────────────────────────────────────

    @PatchMapping("/{id}/assign")
    public CaseResponse assignCase(@PathVariable UUID id,
                                    @RequestParam String agentId,
                                    @AuthenticationPrincipal UserDetails user) {
        return caseService.assignCase(id, agentId, user.getUsername());
    }

    // ── HEALTH CHECK ─────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("FlowOps Case API is UP");
    }
}
