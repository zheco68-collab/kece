package com.kece.solution.mapper;

import com.kece.solution.dto.SolutionQuestionVO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 仅查询题解关联所需的题目元数据，绝不读取题面、用例或参考答案。 */
@Mapper
public interface SolutionQuestionMapper {

    SolutionQuestionVO findAlgoForUpdate(@Param("id") Long id);

    SolutionQuestionVO findDbForUpdate(@Param("id") Long id);

    SolutionQuestionVO findAlgo(@Param("id") Long id);

    SolutionQuestionVO findDb(@Param("id") Long id);

    List<SolutionQuestionVO> findAlgoByIds(@Param("ids") Collection<Long> ids);

    List<SolutionQuestionVO> findDbByIds(@Param("ids") Collection<Long> ids);
}
