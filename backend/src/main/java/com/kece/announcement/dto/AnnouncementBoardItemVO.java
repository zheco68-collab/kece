package com.kece.announcement.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公告看板轻量列表项视图模型。
 * 用于前台看板卡片/列表渲染，不包含大量 Markdown 正文，减少网络传输负载。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementBoardItemVO {

    private Long id;
    private String title;
    private String summary;
    private String authorName;
    private Integer isPinned;
    private Integer priority;
    private Integer viewCount;
    private LocalDateTime createdAt;
}
