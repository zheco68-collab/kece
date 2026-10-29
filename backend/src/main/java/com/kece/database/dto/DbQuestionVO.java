package com.kece.database.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 数据库题目视图对象。标准答案与 DML 校验查询仅对出题人/管理员可见。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbQuestionVO {

    private Long id;
    private String title;
    private String description;
    private String dialect;
    private String type;

    /** 建表语句对所有人可见：用户需要照着它写 SQL。 */
    private String schemaSql;

    /** 标准答案，仅出题人/管理员可见，其余为 null。 */
    private String referenceSql;

    /** DML 校验查询，仅出题人/管理员可见，其余为 null。 */
    private String verifySql;

    private boolean orderSensitive;
    private Integer status;
    private Long createdBy;
    private String authorName;
    private String authorAvatarUrl;
    private List<DbTestCaseVO> testCases;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
