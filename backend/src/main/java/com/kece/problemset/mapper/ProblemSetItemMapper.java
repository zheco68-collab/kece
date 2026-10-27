package com.kece.problemset.mapper;

import com.kece.problemset.entity.ProblemSetItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 题集题目关联快照条目 Mapper。 */
@Mapper
public interface ProblemSetItemMapper {

    int insertBatch(@Param("items") List<ProblemSetItem> items);

    List<ProblemSetItem> findBySetId(@Param("setId") Long setId);

    int deleteBySetId(@Param("setId") Long setId);

    /** 反查包含某道题目的所有题集条目（用于用户 AC 题目时同步更新所有相关题集的进度）。 */
    List<ProblemSetItem> findByQuestion(
            @Param("questionType") String questionType, @Param("questionId") Long questionId);

    /** 当原题被删除或下架时，标记题集条目 source_alive = 0。 */
    int markSourceDead(
            @Param("questionType") String questionType, @Param("questionId") Long questionId);
}
