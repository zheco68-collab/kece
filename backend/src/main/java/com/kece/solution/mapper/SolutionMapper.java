package com.kece.solution.mapper;

import com.kece.solution.entity.Solution;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 题解数据访问；公开性由查询中的关联题目状态共同限定。 */
@Mapper
public interface SolutionMapper {

    int insert(Solution solution);

    Solution findById(@Param("id") Long id);

    Solution findByAuthorAndRequestId(
            @Param("authorId") Long authorId, @Param("clientRequestId") String clientRequestId);

    List<Solution> findPublicPage(
            @Param("questionType") String questionType,
            @Param("questionId") Long questionId,
            @Param("offset") int offset,
            @Param("size") int size);

    long countPublic(
            @Param("questionType") String questionType, @Param("questionId") Long questionId);

    List<Solution> findMinePage(
            @Param("authorId") Long authorId, @Param("offset") int offset, @Param("size") int size);

    long countMine(@Param("authorId") Long authorId);

    List<Solution> findAdminPage(
            @Param("keyword") String keyword,
            @Param("questionId") Long questionId,
            @Param("questionType") String questionType,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("size") int size);

    long countAdmin(
            @Param("keyword") String keyword,
            @Param("questionId") Long questionId,
            @Param("questionType") String questionType,
            @Param("status") String status);

    int updateContentIfVersion(
            @Param("id") Long id,
            @Param("authorId") Long authorId,
            @Param("title") String title,
            @Param("contentMd") String contentMd,
            @Param("version") Long version);

    int updateStatusIfVersion(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("version") Long version);

    int softDeleteIfVersion(@Param("id") Long id, @Param("version") Long version);
}
