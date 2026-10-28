package com.kece.question.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 算法题目实体，映射 {@code question} 表。 */
@Data
public class Question {

    private Long id;
    private String title;

    /** 题目描述（Markdown）。 */
    private String description;

    /** 难度：EASY/MEDIUM/HARD。 */
    private String difficulty;

    /** 时间限制（ms）。 */
    private Integer timeLimit;

    /** 内存限制（MB）。 */
    private Integer memoryLimit;

    /** 支持的语言，逗号分隔。 */
    private String languages;

    /** 算法知识点分类标签，逗号分隔。 */
    private String tags;

    private Long createdBy;

    /** 状态：1 公开 0 私有。 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
