package com.kece.rank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.security.AuthContextHolder;
import com.kece.rank.dto.RankPageVO;
import com.kece.rank.dto.RankType;
import com.kece.rank.dto.RankUserVO;
import com.kece.rank.mapper.RankMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 全域排行榜服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankService {

    private static final String CACHE_KEY_PREFIX = "rank:cache:page:";
    private static final long BASE_TTL_SECONDS = 120L;
    private static final long MAX_JITTER_SECONDS = 30L;

    private final RankMapper rankMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

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
        boolean canCache = (cleanKeyword == null);

        if (canCache) {
            String cacheKey = buildCacheKey(type, validPage, validSize);
            RankPageVO cached = getFromCache(cacheKey);
            if (cached != null) {
                // 如果当前用户已登录，且命中页面缓存，单独快速回填 myRank，确保登录用户总能看到自己的实时排名
                if (currentUserId != null) {
                    cached.setMyRank(fetchUserRank(type, currentUserId));
                } else {
                    cached.setMyRank(null);
                }
                return cached;
            }
        }

        List<RankUserVO> list;
        long total;
        String typeLabel = getTypeLabel(type);

        switch (type) {
            case SQL -> {
                list = rankMapper.findSqlRankings(cleanKeyword, offset, validSize);
                total = rankMapper.countSqlRankings(cleanKeyword);
            }
            case OBJECTIVE -> {
                list = rankMapper.findObjectiveRankings(cleanKeyword, offset, validSize);
                total = rankMapper.countObjectiveRankings(cleanKeyword);
            }
            default -> {
                list = rankMapper.findAlgoRankings(cleanKeyword, offset, validSize);
                total = rankMapper.countAlgoRankings(cleanKeyword);
            }
        }

        RankUserVO myRank = null;
        if (currentUserId != null) {
            myRank = fetchUserRank(type, currentUserId);
        }

        RankPageVO result = RankPageVO.builder()
                .type(type.name())
                .typeLabel(typeLabel)
                .total(total)
                .page(validPage)
                .size(validSize)
                .list(list != null ? list : List.of())
                .myRank(myRank)
                .build();

        if (canCache) {
            String cacheKey = buildCacheKey(type, validPage, validSize);
            // 写入缓存时排除特定登录用户的 myRank，防止多用户间数据串扰
            RankPageVO cacheData = RankPageVO.builder()
                    .type(result.getType())
                    .typeLabel(result.getTypeLabel())
                    .total(result.getTotal())
                    .page(result.getPage())
                    .size(result.getSize())
                    .list(result.getList())
                    .build();
            putToCache(cacheKey, cacheData);
        }

        return result;
    }

    private String buildCacheKey(RankType type, int page, int size) {
        return CACHE_KEY_PREFIX + type.name() + ":" + page + ":" + size;
    }

    private RankPageVO getFromCache(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, RankPageVO.class);
            }
        } catch (Exception e) {
            log.warn("读取排行榜页面缓存失败 key={}: {}", key, e.getMessage());
        }
        return null;
    }

    private void putToCache(String key, RankPageVO data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            long jitter = ThreadLocalRandom.current().nextLong(MAX_JITTER_SECONDS + 1);
            Duration ttl = Duration.ofSeconds(BASE_TTL_SECONDS + jitter);
            redis.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("写入排行榜页面缓存失败 key={}: {}", key, e.getMessage());
        }
    }

    private RankUserVO fetchUserRank(RankType type, Long userId) {
        if (userId == null) {
            return null;
        }
        return switch (type) {
            case SQL -> rankMapper.findUserSqlRank(userId);
            case OBJECTIVE -> rankMapper.findUserObjectiveRank(userId);
            default -> rankMapper.findUserAlgoRank(userId);
        };
    }

    private String getTypeLabel(RankType type) {
        return switch (type) {
            case SQL -> "SQL 数据库对拍榜";
            case OBJECTIVE -> "客观理论积分榜";
            default -> "算法编程天梯榜";
        };
    }
}
