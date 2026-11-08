package com.kece.submission.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 提交记录实体，映射 {@code submission} 表。 */
@Data
public class Submission {

    private Long id;
    private Long userId;
    private Long questionId;
    private Long contestId;
    private Long currentJudgeJobId;

    /** 语言：cpp/java/python。 */
    private String language;

    /** 提交的代码内容（单文件或多文件 JSON）。 */
    private String codeContent;

    /** 评测状态（见 {@code SubmissionStatus}）。 */
    private String status;

    private Integer score;
    private Integer timeUsedMs;
    private Integer memoryUsedKb;
    private String errorLog;

    /** 评测结果详情（JSON，含逐用例报告）。 */
    private String resultJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
