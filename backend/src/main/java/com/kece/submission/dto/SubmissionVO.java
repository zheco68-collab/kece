package com.kece.submission.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 提交状态与结果视图（轮询返回体）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionVO {

    private Long submissionId;
    private String status;
    private ProgressVO progress;
    private SubmissionResultVO result;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String waitReason;
}
