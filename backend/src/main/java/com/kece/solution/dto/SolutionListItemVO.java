package com.kece.solution.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 列表页题解摘要，不携带正文。 */
@Data
public class SolutionListItemVO {

    private Long id;
    private String questionType;
    private Long questionId;
    private String questionTitle;
    private Long authorId;
    private String authorName;
    private String title;
    private String status;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean questionPublic;
}
