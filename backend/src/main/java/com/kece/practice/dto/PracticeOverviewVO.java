package com.kece.practice.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 教师端考务总览大盘 (Overview) 视图对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeOverviewVO {

    private Long practiceId;
    private String title;
    private String description;
    private String accessCode;
    private Integer durationMinutes;
    private String status;
    private Long teacherId;
    private String teacherName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long remainingSeconds;

    /** 考务宏观四宫格指标。 */
    private MetricsCard metrics;

    /** 逐题正答率与考点分析。 */
    private List<QuestionAccuracyItem> questionAccuracies;

    /** 全班学生作答名单。 */
    private List<StudentRosterItem> students;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricsCard {
        private Integer joinedCount;
        private Integer submittedCount;
        private Double submitRate;

        private Double avgScore;
        private Integer maxScore;
        private Integer minScore;

        private Integer totalSubmissions;
        private Integer perfectScoreCount;
        private Integer activeJudgingCount;

        private Integer passCount;
        private Double passRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionAccuracyItem {
        private Long practiceQuestionId;
        private Long questionId;
        private String questionType;
        /** 细分题型或难度：如 SINGLE/MULTIPLE/JUDGE/FILL、SELECT/DML、EASY/MEDIUM/HARD。 */
        private String subType;
        private String title;
        private Integer fullScore;
        private Integer submitCount;
        private Integer passCount;
        private Double passRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudentRosterItem {
        private Long userId;
        private String username;
        private String nickname;
        private Integer totalScore;
        private Integer solvedCount;
        private Boolean submitted;
        private LocalDateTime joinedAt;
        private LocalDateTime submittedAt;
        private LocalDateTime lastActiveAt;
    }
}
