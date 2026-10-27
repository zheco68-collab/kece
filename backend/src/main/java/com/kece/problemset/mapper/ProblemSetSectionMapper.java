package com.kece.problemset.mapper;

import com.kece.problemset.entity.ProblemSetSection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 题集章节分组 Mapper。 */
@Mapper
public interface ProblemSetSectionMapper {

    int insert(ProblemSetSection section);

    List<ProblemSetSection> findBySetId(@Param("setId") Long setId);

    int deleteBySetId(@Param("setId") Long setId);
}
