package com.kece.practice.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 客观题提交判定结果（支持单题即答即存增量判分，同时返回学生最新整卷总分）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeObjectiveResultVO {

    private Integer totalScore;
    private Integer fullScore;
    private Integer correctCount;
    private Integer totalCount;

    /** 当前学生在整场课练中的最新总得分与总攻克数。 */
    private Integer myTotalScore;

    private Integer mySolvedCount;

    private List<ItemResult> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemResult {
        private Long practiceQuestionId;
        private Long questionId;
        private Boolean correct;
        private String status; // ACCEPTED, PARTIAL, WRONG_ANSWER
        private Integer score;
        private Integer fullScore;
        private String userAnswer;
        private String correctAnswer;
    }
}
