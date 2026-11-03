package com.kece.practice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 二次编辑课练请求参数。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePracticeRequest {

    @NotBlank(message = "课练标题不能为空")
    @Size(max = 200, message = "标题不能超过200字符")
    private String title;

    private String description;

    private Integer durationMinutes;

    /** 可选更新的题目列表；若为空则保留原题集不变。 */
    private List<PracticeQuestionItem> questions;
}

