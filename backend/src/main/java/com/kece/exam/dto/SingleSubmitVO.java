package com.kece.exam.dto;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

/** ACM 赛制考试中单题实时提交的返回（submissionId 供前端轮询评测状态）。 */
@Data
@Builder
@Jacksonized
public class SingleSubmitVO {

    /** 代码/数据库提交记录 ID（判题机写回该行）。 */
    private Long submissionId;

    /** 题型：ALGO / DB。 */
    private String questionType;

    /** 初始评测状态（PENDING，前端据此轮询）。 */
    private String status;
}
