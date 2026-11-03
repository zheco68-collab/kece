package com.kece.practice.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 随堂课练正统实时成绩榜 (Scoreboard) 视图对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeScoreboardVO {

    private Long practiceId;
    private String title;
    private String accessCode;
    private String status; // ACTIVE, ENDED
    private Long remainingSeconds;

    /** 题目列元信息（供矩阵表头渲染）。 */
    private List<QuestionHeader> questionHeaders;

    /** 全班各题 AC 率基准行。 */
    private List<QuestionStatBenchmark> benchmarks;

    /** 实时排名榜行列表（按 ICPC 规则排序）。 */
    private List<RankRow> rows;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionHeader {
        private Long practiceQuestionId;
        private Long questionId;
        private String questionType; // OBJECTIVE, ALGO, DB
        private String title;
        private Integer score;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionStatBenchmark {
        private Long practiceQuestionId;
        private Integer submitUserCount;
        private Integer passUserCount;
        private Double passRate; // 百分比
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankRow {
        private Integer rank;
        private Long userId;
        private String username;
        private String nickname;
        private Integer totalScore;
        private Integer solvedCount;
        private Long penaltyTime; // 累计用时/罚时(秒)
        private Boolean submitted;
        private LocalDateTime lastSubmitTime;
        private Boolean isCurrentUser;

        /** 各题单元格作答状态（key: practiceQuestionId）。 */
        private List<CellState> cellStates;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CellState {
        private Long practiceQuestionId;
        private String status; // ACCEPTED, PARTIAL, WRONG_ANSWER, UNATTEMPTED
        private Integer score;
        private Integer attempts; // 尝试次数
        private Long elapsedMinutes; // 首次AC或最新通过耗时(分钟)
    }
}

