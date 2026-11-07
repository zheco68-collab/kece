package com.kece.admin.dto;

import java.time.Instant;
import java.util.List;

public record AdminOverviewActivities(Instant asOf, Group active, Group upcoming, Group ended) {
    public record Group(long total, List<Activity> items) {}

    public record Activity(
            String kind, long id, String title, Instant startAt, Instant endAt, Long memberCount) {}
}
