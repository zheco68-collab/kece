package com.kece.question.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/** 算法题目创建/更新请求。 */
@Data
public class QuestionRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题长度不能超过 255")
    private String title;

    @NotBlank(message = "题目描述不能为空")
    private String description;

    @NotBlank(message = "难度不能为空")
    private String difficulty;

    @Min(value = 1, message = "时间限制不能小于 1ms")
    @Max(value = 60000, message = "时间限制不能大于 60000ms")
    private Integer timeLimit = 1000;

    @Min(value = 1, message = "内存限制不能小于 1MB")
    @Max(value = 4096, message = "内存限制不能大于 4096MB")
    private Integer memoryLimit = 256;

    private String languages = "cpp,java,python";

    /** 算法分类标签列表。 */
    private List<String> tags;

    /** 状态：1 公开 0 私有。 */
    @Min(value = 0, message = "状态取值只能为 0 或 1")
    @Max(value = 1, message = "状态取值只能为 0 或 1")
    private Integer status = 1;
}
