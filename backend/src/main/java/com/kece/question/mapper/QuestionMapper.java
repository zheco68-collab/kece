package com.kece.question.mapper;

import com.kece.question.entity.Question;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 算法题目 Mapper。 */
@Mapper
public interface QuestionMapper {

    int insert(Question question);

    int update(Question question);

    int deleteById(Long id);

    Question findById(Long id);

    /** 按标题精确查重（种子播种幂等用）。 */
    Question findByTitle(@Param("title") String title);

    List<Question> findPage(
            @Param("keyword") String keyword,
            @Param("difficulty") String difficulty,
            @Param("tag") String tag,
            @Param("status") Integer status,
            @Param("createdBy") Long createdBy,
            @Param("includeIds") List<Long> includeIds,
            @Param("excludeIds") List<Long> excludeIds,
            @Param("offset") int offset,
            @Param("size") int size);

    long count(
            @Param("keyword") String keyword,
            @Param("difficulty") String difficulty,
            @Param("tag") String tag,
            @Param("status") Integer status,
            @Param("createdBy") Long createdBy,
            @Param("includeIds") List<Long> includeIds,
            @Param("excludeIds") List<Long> excludeIds);

    /** 查询所有已使用的非空标签列表。 */
    List<String> findAllRawTags();
}
