package com.kece.announcement.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台系统公告实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Announcement {

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
