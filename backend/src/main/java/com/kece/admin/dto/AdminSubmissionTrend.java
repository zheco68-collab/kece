package com.kece.admin.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdminSubmissionTrend(Instant asOf, List<Bucket> buckets) {
    public record Bucket(LocalDate from, LocalDate to, long algoCount, long dbCount) {}
}
