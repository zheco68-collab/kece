package com.kece.database.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 数据库测试点视图对象：只暴露一套初始数据。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbTestCaseVO {

    private Long id;
    private Integer caseOrder;
    private String seedSql;
    private String expectedOutput;

    /**
     * 0 非样例 / 1 样例。用 Integer 而非 boolean：boolean 字段名以 is 开头时 Lombok 生成的 isSample() 会被 Jackson 推导为属性名
     * sample，与前端约定不符。
     */
    private Integer isSample;
}
