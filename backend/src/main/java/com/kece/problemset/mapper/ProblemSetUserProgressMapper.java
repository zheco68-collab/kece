package com.kece.problemset.mapper;

import com.kece.problemset.entity.ProblemSetUserProgress;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 用户题集进度与收藏预聚合 Mapper。 */
@Mapper
public interface ProblemSetUserProgressMapper {

    ProblemSetUserProgress findBySetAndUser(
            @Param("setId") Long setId, @Param("userId") Long userId);

    List<ProblemSetUserProgress> findByUserAndSetIds(
            @Param("userId") Long userId, @Param("setIds") List<Long> setIds);

    List<Long> findFavoritedSetIds(@Param("userId") Long userId);

    /** 幂等写入或更新用户题集进度（Read-Repair 与实时 AC 同步共用）。 */
    int upsertProgress(
            @Param("setId") Long setId,
            @Param("userId") Long userId,
            @Param("solvedCount") int solvedCount,
            @Param("solvedItemIds") String solvedItemIds);

    /** 幂等更新收藏状态。 */
    int upsertFavorite(
            @Param("setId") Long setId,
            @Param("userId") Long userId,
            @Param("isFavorited") int isFavorited);

    /** 记录用户最近练习的题目条目（断点续刷）。 */
    int upsertLastItem(
            @Param("setId") Long setId,
            @Param("userId") Long userId,
            @Param("lastItemId") Long lastItemId);
}
