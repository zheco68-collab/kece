package com.kece.problemset.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 题集章节分组视图模型。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemSetSectionVO {

    private Long id;
    private String title;
    private String description;
    private Integer sortOrder;
    private Integer totalCount;
    private Integer solvedCount;
    private List<ProblemSetItemVO> items;
}
