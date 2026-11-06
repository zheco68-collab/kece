package com.kece.solution.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 用户发布的题解，正文保存为原始 Markdown。 */
@Data
public class Solution {

    private Long id;
    private String questionType;
    private Long questionId;
    private Long authorId;
    private String title;
    private String contentMd;
    private String status;
    private Long version;
    private String clientRequestId;
    private String requestHash;
    private String lastManagementReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
