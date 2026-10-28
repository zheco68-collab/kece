package com.kece.question.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/** 算法题目视图对象。 */
@Data
@Builder
public class QuestionVO {

    private Long id;
    private String title;
    private String description;
    private String difficulty;
    private Integer timeLimit;
    private Integer memoryLimit;
    private List<String> languages;
    private List<String> tags;
    private Integer status;
    private Long createdBy;
    private String authorName;
    private String authorAvatarUrl;

    /** 对用户可见的样例用例。 */
    private List<TestCaseVO> sampleTestCases;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
