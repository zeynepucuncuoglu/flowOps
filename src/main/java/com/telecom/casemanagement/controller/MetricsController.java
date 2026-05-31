package com.telecom.casemanagement.controller;

import com.telecom.casemanagement.model.CasePriority;
import com.telecom.casemanagement.model.CaseStatus;
import com.telecom.casemanagement.repository.CustomerCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Business-level dashboard endpoint — consumed by Grafana JSON data source plugin
 * and the internal ops portal. Separate from /actuator/prometheus (which is
 * Prometheus-scrape format only).
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final CustomerCaseRepository caseRepository;

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (CaseStatus s : CaseStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        caseRepository.countByStatus()
                      .forEach(row -> byStatus.put(((CaseStatus) row[0]).name(), (Long) row[1]));

        Map<String, Long> criticalByStatus = new LinkedHashMap<>();
        Arrays.stream(CaseStatus.values()).forEach(s ->
            criticalByStatus.put(s.name(),
                caseRepository.countByStatusAndPriority(s, CasePriority.CRITICAL)));

        return Map.of(
            "casesByStatus", byStatus,
            "criticalCasesByStatus", criticalByStatus
        );
    }
}
