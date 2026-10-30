package com.kece.announcement.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公告完整详情视图模型（含 Markdown 正文）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementDetailVO {

    private Long id;
    private String title;
    private String summary;
    private String content;
    private String authorName;
    private Integer isPinned;
    private Integer priority;
    private String status;
    private Integer viewCount;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
