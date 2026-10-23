package com.kece.question.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 测试用例轻量汇总视图对象（仅元数据与统计，不含测试点文本数据）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseSummaryVO {

    private Integer totalCount;
    private Integer sampleCount;
    private Long totalInputBytes;
    private Long totalOutputBytes;
    private Long totalBytes;
}
