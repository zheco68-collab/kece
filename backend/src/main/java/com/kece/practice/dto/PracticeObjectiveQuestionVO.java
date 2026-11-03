package com.kece.practice.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 课练客观题详细项。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeObjectiveQuestionVO {

    private Long practiceQuestionId;
    private Long questionId;
    private String type; // SINGLE, MULTIPLE, JUDGE, FILL
    private String content;
    private List<String> options;
    private Integer score;
    private Integer sortOrder;

    /** 当前登录用户的作答状态。 */
    private String userAnswer;

    private Integer userScore;
    private Boolean isCorrect;
    private Boolean answered;

    /** 标准答案（仅在课练已截止、学生已交卷或教师管理预览时返回，未交卷作答中严格置 null）。 */
    private String correctAnswer;
}
