package com.kece.announcement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发布与编辑公告请求体（含 JSR-303 字段校验）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementRequest {

    @NotBlank(message = "公告标题不能为空")
    @Size(max = 128, message = "公告标题长度不能超过128个字符")
    private String title;

    @Size(max = 255, message = "导读摘要长度不能超过255个字符")
    private String summary;

    @NotBlank(message = "公告正文内容不能为空")
    private String content;

    @Size(max = 64, message = "发布人名称长度不能超过64个字符")
    private String authorName;

    private Integer isPinned;

    private Integer priority;

    private String status;
}
