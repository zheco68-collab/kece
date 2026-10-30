package com.kece.announcement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 切换/设置公告置顶状态请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementPinnedRequest {

    /**
     * 目标置顶状态：1-置顶，0-普通。若为空则反转当前置顶状态。
     */
    private Integer isPinned;
}
