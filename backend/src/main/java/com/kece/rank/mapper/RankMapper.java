package com.kece.rank.mapper;

import com.kece.rank.dto.RankUserVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 全域天梯排行榜 Mapper。 */
@Mapper
public interface RankMapper {

    // ---- 编程算法榜 (ALGO) ----

    List<RankUserVO> findAlgoRankings(
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("size") int size);

    long countAlgoRankings(@Param("keyword") String keyword);

    RankUserVO findUserAlgoRank(@Param("userId") Long userId);

    // ---- 数据库 SQL 榜 (SQL) ----

    List<RankUserVO> findSqlRankings(
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("size") int size);

    long countSqlRankings(@Param("keyword") String keyword);

    RankUserVO findUserSqlRank(@Param("userId") Long userId);

    // ---- 客观题积分榜 (OBJECTIVE) ----

    List<RankUserVO> findObjectiveRankings(
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("size") int size);

    long countObjectiveRankings(@Param("keyword") String keyword);

    RankUserVO findUserObjectiveRank(@Param("userId") Long userId);
}
