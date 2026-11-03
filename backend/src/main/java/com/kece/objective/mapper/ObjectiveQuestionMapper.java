package com.kece.objective.mapper;

import com.kece.objective.entity.ObjectiveQuestion;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 客观题 Mapper。 */
@Mapper
public interface ObjectiveQuestionMapper {

    int insert(ObjectiveQuestion question);

    int update(ObjectiveQuestion question);

    int deleteById(Long id);

    ObjectiveQuestion findById(Long id);

    /** 按题干精确查找，供种子数据幂等判重。 */
    ObjectiveQuestion findByContent(@Param("content") String content);

    List<ObjectiveQuestion> findPage(
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("createdBy") Long createdBy,
            @Param("id") Long id,
            @Param("offset") int offset,
            @Param("size") int size);

    long count(
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("createdBy") Long createdBy,
            @Param("id") Long id);
}
