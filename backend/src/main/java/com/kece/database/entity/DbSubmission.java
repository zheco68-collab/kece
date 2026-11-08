package com.kece.database.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 数据库提交记录实体，映射 {@code db_submission} 表。 */
@Data
public class DbSubmission {

    private Long id;
    private Long userId;
    private Long questionId;

    /** 考试场次 ID（考试数据库题复用评测链路时关联）。 */
    private Long contestId;

    private Long currentJudgeJobId;

    /** SQL 方言：MYSQL。 */
    private String dialect;

    /** 提交的 SQL（单条语句）。 */
    private String sqlContent;

    /** 评测状态（见 {@code SubmissionStatus}）。 */
    private String status;

    private Integer score;
    private Integer timeUsedMs;

    /** 评测结果详情（JSON，含逐用例报告）。 */
    private String resultJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
