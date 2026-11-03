package com.kece.database.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 数据库题目作答请求。 */
@Data
public class DbSubmitRequest {

    @NotBlank(message = "SQL 不能为空")
    private String sql;

    /** 随堂课练房间口令（选填，传入时在同一事务内原子绑定课练提交）。 */
    private String practiceCode;

    /** 随堂课练题目关联ID（选填，对应 lesson_practice_question.id）。 */
    private Long practiceQuestionId;
}
