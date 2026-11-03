package com.kece.submission.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.Data;

/** 提交代码请求。 */
@Data
public class SubmitRequest {

    @NotNull(message = "题目ID不能为空")
    private Long questionId;

    /** 比赛/考试ID（选填）。 */
    private Long contestId;

    /** 随堂课练房间口令（选填，传入时在同一事务内原子绑定课练提交）。 */
    private String practiceCode;

    /** 随堂课练题目关联ID（选填，对应 lesson_practice_question.id）。 */
    private Long practiceQuestionId;

    @NotNull(message = "语言不能为空")
    private String language;

    /** 代码内容：文件名到内容的映射，如 {@code {"main.cpp": "..."}}。 */
    @NotEmpty(message = "代码内容不能为空")
    private Map<String, String> codeContent;
}
