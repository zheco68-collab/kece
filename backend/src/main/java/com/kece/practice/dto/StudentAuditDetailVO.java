package com.kece.practice.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 教师调阅指定学生作答详情全息审计视图对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentAuditDetailVO {

    private Long userId;
    private String username;
    private String nickname;
    private Integer totalScore;
    private Integer solvedCount;
    private Boolean submitted;
    private LocalDateTime joinedAt;
    private LocalDateTime submittedAt;

    private List<AuditQuestionItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditQuestionItem {
        private Long practiceQuestionId;
        private Long questionId;
        private String questionType; // OBJECTIVE, ALGO, DB
        private String title;
        private Integer fullScore;
        private Integer earnedScore;
        private String status; // ACCEPTED, PARTIAL, WRONG_ANSWER, UNATTEMPTED

        // 客观题特有
        private String objectiveType; // SINGLE, MULTIPLE, JUDGE
        private String objectiveContent;
        private List<String> objectiveOptions;
        private String userAnswer;
        private String correctAnswer;

        // 实操题特有 (算法 / SQL)
        private Long submissionId;
        private String code; // 完整代码快照
        private String language;
        private Integer timeMs;
        private Integer memoryKb;
        private String judgeResultJson; // 包含各测试点通过详情
        private LocalDateTime submittedAt;
    }
}

