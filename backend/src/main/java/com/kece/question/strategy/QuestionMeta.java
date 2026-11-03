package com.kece.question.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 题目元信息（统一不同存储实体的标题、创建者与默认分值等元数据）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionMeta {

    /** 题目在原存储表中的业务ID */
    private Long id;

    /** 题型代码（如 ALGO、DB、OBJECTIVE、FRONTEND） */
    private String typeCode;

    /** 题目标题或内容简写摘要 */
    private String title;

    /** 题目详细描述或内容 */
    private String description;

    /** 出题教师或管理员用户ID */
    private Long createdBy;

    /** 题目默认分值或满分上限 */
    private Integer defaultScore;

    /** 难度等级（EASY/MEDIUM/HARD 等） */
    private String difficulty;
}
