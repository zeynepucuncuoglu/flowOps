package com.telecom.casemanagement.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class CaseNumberGenerator {

    private final AtomicLong sequence = new AtomicLong(
            System.currentTimeMillis() % 100_000
    );

    public String next() {
        int year = LocalDate.now().getYear();
        long seq  = sequence.incrementAndGet();
        return String.format("CASE-%d-%05d", year, seq % 100_000);
    }
}
