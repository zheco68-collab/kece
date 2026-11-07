package com.kece.admin.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdminOverviewMapper {
    List<ActivityRow> listActivities(
            @Param("ownerId") Long ownerId,
            @Param("now") LocalDateTime now,
            @Param("recentStart") LocalDateTime recentStart,
            @Param("upcomingEnd") LocalDateTime upcomingEnd);

    List<SubmissionDay> countSubmissionDays(
            @Param("ownerId") Long ownerId,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);

    record ActivityRow(
            String bucket,
            String kind,
            long id,
            String title,
            LocalDateTime startTime,
            LocalDateTime endTime,
            long bucketSize,
            Long memberCount) {}

    record SubmissionDay(LocalDate date, long algoCount, long dbCount) {}
}
