package com.kece.database.mapper;

import com.kece.database.entity.DbQuestion;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 数据库题目 Mapper。 */
@Mapper
public interface DbQuestionMapper {

    int insert(DbQuestion question);

    int update(DbQuestion question);

    int deleteById(Long id);

    DbQuestion findById(Long id);

    DbQuestion findByIdForUpdate(Long id);

    /** 按标题精确查重（种子播种幂等用）。 */
    DbQuestion findByTitle(@Param("title") String title);

    List<DbQuestion> findPage(
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("createdBy") Long createdBy,
            @Param("includeIds") List<Long> includeIds,
            @Param("excludeIds") List<Long> excludeIds,
            @Param("offset") int offset,
            @Param("size") int size);

    long count(
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("createdBy") Long createdBy,
            @Param("includeIds") List<Long> includeIds,
            @Param("excludeIds") List<Long> excludeIds);
}
