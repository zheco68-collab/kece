package com.kece.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.rank.dto.RankPageVO;
import com.kece.rank.dto.RankUserVO;
import com.kece.rank.mapper.RankMapper;
import com.kece.rank.service.RankService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RankServiceCacheTest {

    private RankMapper rankMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private RankService rankService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rankMapper = mock(RankMapper.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        rankService = new RankService(rankMapper, redis, objectMapper);
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    @DisplayName("无搜索关键字时，缓存未命中应查库并写入带 120s+Jitter TTL 的 Redis 缓存")
    void getRankings_onCacheMiss_shouldQueryDbAndPopulateRedis() {
        String expectedKey = "rank:cache:page:ALGO:1:50";
        when(valueOps.get(expectedKey)).thenReturn(null);

        RankUserVO user1 = RankUserVO.builder()
                .rank(1)
                .userId(101L)
                .username("alice")
                .solvedCount(100)
                .totalSubmissions(120)
                .acSubmissions(100)
                .acceptRate(83.3)
                .lastAcceptedAt(LocalDateTime.now())
                .build();

        when(rankMapper.findAlgoRankings(null, 0, 50)).thenReturn(List.of(user1));
        when(rankMapper.countAlgoRankings(null)).thenReturn(1L);

        RankPageVO result = rankService.getRankings("ALGO", 1, 50, null);

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo("ALGO");
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getUsername()).isEqualTo("alice");

        // 验证数据库查询执行
        verify(rankMapper).findAlgoRankings(null, 0, 50);
        verify(rankMapper).countAlgoRankings(null);

        // 验证写入 Redis 缓存及 TTL (120 秒 + 0~30 秒 Jitter)
        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(expectedKey), anyString(), durationCaptor.capture());
        Duration capturedDuration = durationCaptor.getValue();
        assertThat(capturedDuration.toSeconds()).isBetween(120L, 150L);
    }

    @Test
    @DisplayName("未登录状态下缓存命中时，应短路跳过所有 Mapper 查询直接返回缓存数据")
    void getRankings_onCacheHit_withoutLogin_shouldShortCircuitMapper() throws Exception {
        String cacheKey = "rank:cache:page:ALGO:1:50";
        RankPageVO cachedVO = RankPageVO.builder()
                .type("ALGO")
                .typeLabel("算法编程天梯榜")
                .total(42L)
                .page(1)
                .size(50)
                .list(List.of(
                        RankUserVO.builder()
                                .rank(1)
                                .userId(201L)
                                .username("bob")
                                .solvedCount(88)
                                .build()))
                .build();
        String json = objectMapper.writeValueAsString(cachedVO);
        when(valueOps.get(cacheKey)).thenReturn(json);

        RankPageVO result = rankService.getRankings("ALGO", 1, 50, "");

        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isEqualTo(42L);
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getUsername()).isEqualTo("bob");
        assertThat(result.getMyRank()).isNull();

        // 验证彻底短路，Mapper 无任何交互
        verifyNoInteractions(rankMapper);
    }

    @Test
    @DisplayName("已登录状态下命中页面缓存，应单独快速回填当前用户的 myRank，且不查列表和总数")
    void getRankings_onCacheHit_withLogin_shouldBackfillMyRank() throws Exception {
        Long currentUserId = 999L;
        AuthContextHolder.set(new AuthUser(currentUserId, "charlie", Set.of(Role.USER.name())));

        String cacheKey = "rank:cache:page:SQL:1:50";
        RankPageVO cachedVO = RankPageVO.builder()
                .type("SQL")
                .typeLabel("SQL 数据库对拍榜")
                .total(100L)
                .page(1)
                .size(50)
                .list(List.of(
                        RankUserVO.builder().rank(1).userId(1L).username("top1").build()))
                .build();
        String json = objectMapper.writeValueAsString(cachedVO);
        when(valueOps.get(cacheKey)).thenReturn(json);

        RankUserVO myRank = RankUserVO.builder()
                .rank(18)
                .userId(currentUserId)
                .username("charlie")
                .solvedCount(35)
                .build();
        when(rankMapper.findUserSqlRank(currentUserId)).thenReturn(myRank);

        RankPageVO result = rankService.getRankings("SQL", 1, 50, null);

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo("SQL");
        assertThat(result.getTotal()).isEqualTo(100L);
        assertThat(result.getMyRank()).isNotNull();
        assertThat(result.getMyRank().getRank()).isEqualTo(18);
        assertThat(result.getMyRank().getUsername()).isEqualTo("charlie");

        // 验证短路跳过分页列表与总数查询，仅回填个人排名
        verify(rankMapper, never()).findSqlRankings(any(), anyInt(), anyInt());
        verify(rankMapper, never()).countSqlRankings(any());
        verify(rankMapper).findUserSqlRank(currentUserId);
    }

    @Test
    @DisplayName("有搜索关键字时不走页面缓存，直接查库且不写入缓存")
    void getRankings_withKeyword_shouldBypassCacheAndQueryDbDirectly() {
        String keyword = "developer";
        when(rankMapper.findAlgoRankings(eq(keyword), eq(0), eq(50)))
                .thenReturn(List.of(RankUserVO.builder().rank(5).username("developer_1").build()));
        when(rankMapper.countAlgoRankings(eq(keyword))).thenReturn(1L);

        RankPageVO result = rankService.getRankings("ALGO", 1, 50, "  developer  ");

        assertThat(result).isNotNull();
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getUsername()).isEqualTo("developer_1");

        // 验证完全不与 Redis 进行 get/set 交互
        verify(valueOps, never()).get(anyString());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));

        // 验证带清洗后的 keyword 查库
        verify(rankMapper).findAlgoRankings(eq(keyword), eq(0), eq(50));
        verify(rankMapper).countAlgoRankings(eq(keyword));
    }

    @Test
    @DisplayName("Redis 异常时不阻断业务，自动降级回退到数据库查询")
    void getRankings_whenRedisFails_shouldFallbackToDbGracefully() {
        String cacheKey = "rank:cache:page:OBJECTIVE:1:50";
        when(valueOps.get(cacheKey)).thenThrow(new RuntimeException("Redis connection error"));

        when(rankMapper.findObjectiveRankings(null, 0, 50))
                .thenReturn(List.of(RankUserVO.builder().rank(1).username("obj_master").build()));
        when(rankMapper.countObjectiveRankings(null)).thenReturn(1L);

        RankPageVO result = rankService.getRankings("OBJECTIVE", 1, 50, null);

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo("OBJECTIVE");
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getUsername()).isEqualTo("obj_master");

        // 验证降级执行了数据库查询
        verify(rankMapper).findObjectiveRankings(null, 0, 50);
        verify(rankMapper).countObjectiveRankings(null);
    }
}
