package com.kece.problemset.mapper;

import com.kece.problemset.entity.ProblemSet;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 题集主表 Mapper。 */
@Mapper
public interface ProblemSetMapper {

    int insert(ProblemSet problemSet);

    ProblemSet findById(@Param("id") Long id);

    ProblemSet findByTitle(@Param("title") String title);

    List<ProblemSet> findByIds(@Param("ids") List<Long> ids);

    /** 前台公开题集分页列表。 */
    List<ProblemSet> findPublicPage(
            @Param("category") String category,
            @Param("difficulty") String difficulty,
            @Param("keyword") String keyword,
            @Param("onlyIds") List<Long> onlyIds,
            @Param("offset") int offset,
            @Param("size") int size);

    long countPublic(
            @Param("category") String category,
            @Param("difficulty") String difficulty,
            @Param("keyword") String keyword,
            @Param("onlyIds") List<Long> onlyIds);

    /** 后台管理题集分页列表（ADMIN 全量，TEACHER 仅本人创建）。 */
    List<ProblemSet> findAdminPage(
            @Param("category") String category,
            @Param("keyword") String keyword,
            @Param("createdBy") Long createdBy,
            @Param("offset") int offset,
            @Param("size") int size);

    long countAdmin(
            @Param("category") String category,
            @Param("keyword") String keyword,
            @Param("createdBy") Long createdBy);

    /** 基于乐观锁更新题集主表。 */
    int updateWithVersion(ProblemSet problemSet);

    /** 软删除题集（status = -1）。 */
    int softDelete(@Param("id") Long id);

    /** 增减参与/收藏人数（保底不低于 0）。 */
    int adjustParticipantCount(@Param("id") Long id, @Param("delta") int delta);
}
