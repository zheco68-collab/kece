package com.kece.problemset.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;

/** 创建或更新题集请求体（支持分章节或平铺题目编排，携带乐观锁版本号）。 */
@Data
public class SaveProblemSetRequest {

    @NotBlank(message = "题集标题不能为空")
    private String title;

    private String subtitle;
    private String description;
    private String category; // ALGO / DB / OBJECTIVE / MIXED / COURSE
    private String difficulty; // EASY / MEDIUM / HARD
    private String tags;
    private Integer isOfficial;
    private Integer status; // 1公开 0私有 2归档
    private Integer version; // 更新时的乐观锁版本号

    private List<SectionInput> sections;
    private List<ItemInput> items;

    @Data
    public static class SectionInput {
        private String title;
        private String description;
        private Integer sortOrder;
        private List<ItemInput> items;
    }

    @Data
    public static class ItemInput {
        private String questionType; // ALGO / DB / OBJECTIVE
        private Long questionId;
        private String title;
        private String difficulty;
        private Integer sortOrder;
    }
}
