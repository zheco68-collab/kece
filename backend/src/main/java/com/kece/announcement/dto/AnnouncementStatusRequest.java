package com.kece.announcement.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 修改公告上下架状态请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementStatusRequest {

    /**
     * 目标状态：PUBLISHED / DRAFT / ARCHIVED
     */
    @NotBlank(message = "公告状态不能为空")
    private String status;
}
