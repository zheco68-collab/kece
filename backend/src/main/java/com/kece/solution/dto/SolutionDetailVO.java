package com.kece.solution.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 阅读、编辑和管理操作返回的完整题解。 */
@Data
public class SolutionDetailVO {

    private Long id;
    private String questionType;
    private Long questionId;
    private String questionTitle;
    private Long authorId;
    private String authorName;
    private String title;
    private String contentMd;
    private String status;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean questionPublic;
    private String lastManagementReason;
}
