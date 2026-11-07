package com.kece.admin.service;

import com.kece.admin.dto.AdminOverviewActivities;
import com.kece.admin.dto.AdminOverviewActivities.Activity;
import com.kece.admin.dto.AdminOverviewActivities.Group;
import com.kece.admin.dto.AdminSubmissionTrend;
import com.kece.admin.dto.AdminSubmissionTrend.Bucket;
import com.kece.admin.mapper.AdminOverviewMapper;
import com.kece.admin.mapper.AdminOverviewMapper.ActivityRow;
import com.kece.admin.mapper.AdminOverviewMapper.SubmissionDay;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminOverviewService {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final AdminOverviewMapper mapper;

    public AdminOverviewActivities activities() {
        Long ownerId = ownerFilter();
        ZonedDateTime asOf = ZonedDateTime.now(BEIJING);
        LocalDateTime now = asOf.toLocalDateTime();
        List<ActivityRow> rows =
                mapper.listActivities(ownerId, now, now.minusDays(7), now.plusDays(7));
        return new AdminOverviewActivities(
                asOf.toInstant(),
                group(rows, "ACTIVE"),
                group(rows, "UPCOMING"),
                group(rows, "ENDED"));
    }

    public AdminSubmissionTrend submissionTrend(int days) {
        Long ownerId = ownerFilter();
        if (days != 7 && days != 28) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅支持最近 7 天或 28 天");
        }
        ZonedDateTime asOf = ZonedDateTime.now(BEIJING);
        LocalDate until = asOf.toLocalDate();
        LocalDate from = until.minusDays(days);
        Map<LocalDate, SubmissionDay> counts = new HashMap<>();
        for (SubmissionDay day :
                mapper.countSubmissionDays(ownerId, from.atStartOfDay(), until.atStartOfDay())) {
            counts.put(day.date(), day);
        }

        int bucketDays = days == 7 ? 1 : 7;
        List<Bucket> buckets = new ArrayList<>();
        for (LocalDate start = from; start.isBefore(until); start = start.plusDays(bucketDays)) {
            long algoCount = 0;
            long dbCount = 0;
            for (int offset = 0; offset < bucketDays; offset++) {
                SubmissionDay day = counts.get(start.plusDays(offset));
                if (day != null) {
                    algoCount += day.algoCount();
                    dbCount += day.dbCount();
                }
            }
            buckets.add(new Bucket(start, start.plusDays(bucketDays - 1), algoCount, dbCount));
        }
        return new AdminSubmissionTrend(asOf.toInstant(), buckets);
    }

    private static Group group(List<ActivityRow> rows, String bucket) {
        List<ActivityRow> matches =
                rows.stream().filter(row -> bucket.equals(row.bucket())).toList();
        long total = matches.isEmpty() ? 0 : matches.get(0).bucketSize();
        List<Activity> items =
                matches.stream()
                        .map(
                                row ->
                                        new Activity(
                                                row.kind(),
                                                row.id(),
                                                row.title(),
                                                toInstant(row.startTime()),
                                                toInstant(row.endTime()),
                                                row.memberCount()))
                        .toList();
        return new Group(total, items);
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(BEIJING).toInstant();
    }

    private static Long ownerFilter() {
        AuthUser user = AuthContextHolder.get();
        if (user == null || user.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (user.hasRole(Role.ADMIN)) {
            return null;
        }
        if (user.hasRole(Role.TEACHER)) {
            return user.getId();
        }
        throw new BusinessException(ResultCode.FORBIDDEN);
    }
}
