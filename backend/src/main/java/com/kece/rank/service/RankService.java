package com.kece.rank.service;

import com.kece.common.security.AuthContextHolder;
import com.kece.rank.dto.RankPageVO;
import com.kece.rank.dto.RankType;
import com.kece.rank.dto.RankUserVO;
import com.kece.rank.mapper.RankMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 全域排行榜服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankService {

    private final RankMapper rankMapper;

    /**
     * 获取指定分类的分页排行榜。
     *
     * @param typeStr 分类代码（ALGO / SQL / OBJECTIVE）
     * @param page 页码（1 起始）
     * @param size 每页条数
     * @param keyword 搜索关键字（模糊匹配用户名）
     * @return 分页排行榜视图
     */
    @Transactional(readOnly = true)
    public RankPageVO getRankings(String typeStr, int page, int size, String keyword) {
        RankType type = RankType.from(typeStr);
        int validPage = Math.max(1, page);
        int validSize = Math.max(1, Math.min(100, size));
        int offset = (validPage - 1) * validSize;
        String cleanKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;

        Long currentUserId = AuthContextHolder.currentUserId();

        List<RankUserVO> list;
        long total;
        RankUserVO myRank = null;
        String typeLabel;

        switch (type) {
            case SQL -> {
                typeLabel = "SQL 数据库对拍榜";
                list = rankMapper.findSqlRankings(cleanKeyword, offset, validSize);
                total = rankMapper.countSqlRankings(cleanKeyword);
                if (currentUserId != null) {
                    myRank = rankMapper.findUserSqlRank(currentUserId);
                }
            }
            case OBJECTIVE -> {
                typeLabel = "客观理论积分榜";
                list = rankMapper.findObjectiveRankings(cleanKeyword, offset, validSize);
                total = rankMapper.countObjectiveRankings(cleanKeyword);
                if (currentUserId != null) {
                    myRank = rankMapper.findUserObjectiveRank(currentUserId);
                }
            }
            default -> {
                typeLabel = "算法编程天梯榜";
                list = rankMapper.findAlgoRankings(cleanKeyword, offset, validSize);
                total = rankMapper.countAlgoRankings(cleanKeyword);
                if (currentUserId != null) {
                    myRank = rankMapper.findUserAlgoRank(currentUserId);
                }
            }
        }

        return RankPageVO.builder()
                .type(type.name())
                .typeLabel(typeLabel)
                .total(total)
                .page(validPage)
                .size(validSize)
                .list(list != null ? list : List.of())
                .myRank(myRank)
                .build();
    }
}
