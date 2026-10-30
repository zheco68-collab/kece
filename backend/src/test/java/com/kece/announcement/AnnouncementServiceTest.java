package com.kece.announcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kece.announcement.dto.AnnouncementBoardItemVO;
import com.kece.announcement.dto.AnnouncementDetailVO;
import com.kece.announcement.dto.AnnouncementRequest;
import com.kece.announcement.entity.Announcement;
import com.kece.announcement.mapper.AnnouncementMapper;
import com.kece.announcement.service.impl.AnnouncementServiceImpl;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageResult;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
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

class AnnouncementServiceTest {

    private AnnouncementMapper mapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private AnnouncementServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(AnnouncementMapper.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        service = new AnnouncementServiceImpl(mapper, redis, objectMapper);
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    @DisplayName("前台看板列表：缓存命中时直接返回缓存数据，不查询数据库")
    void listBoard_cacheHit() throws Exception {
        AnnouncementBoardItemVO item = AnnouncementBoardItemVO.builder()
                .id(1L)
                .title("机考通知")
                .summary("机考摘要")
                .authorName("admin")
                .isPinned(1)
                .priority(100)
                .viewCount(10)
                .createdAt(LocalDateTime.now())
                .build();
        PageResult<AnnouncementBoardItemVO> cachedPage = new PageResult<>(List.of(item), 1L, 1, 10);
        String json = objectMapper.writeValueAsString(cachedPage);

        when(valueOps.get("notice:board:page:1:10")).thenReturn(json);

        PageResult<AnnouncementBoardItemVO> result = service.listBoard(1, 10);

        assertThat(result).isNotNull();
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getTitle()).isEqualTo("机考通知");
        verify(mapper, never()).findBoardPage(anyInt(), anyInt());
    }

    @Test
    @DisplayName("前台看板列表：缓存未命中时查库并写入缓存")
    void listBoard_cacheMiss() {
        when(valueOps.get(anyString())).thenReturn(null);

        AnnouncementBoardItemVO item = AnnouncementBoardItemVO.builder()
                .id(2L)
                .title("维护公告")
                .summary("系统升级摘要")
                .authorName("admin")
                .isPinned(0)
                .priority(5)
                .viewCount(3)
                .createdAt(LocalDateTime.now())
                .build();
        when(mapper.findBoardPage(0, 10)).thenReturn(List.of(item));
        when(mapper.countBoard()).thenReturn(1L);

        PageResult<AnnouncementBoardItemVO> result = service.listBoard(1, 10);

        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getList().get(0).getTitle()).isEqualTo("维护公告");

        verify(mapper).findBoardPage(0, 10);
        verify(mapper).countBoard();
        verify(valueOps).set(eq("notice:board:page:1:10"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("公告详情：阅读量原子自增并返回详情")
    void getDetail_success() {
        Announcement announcement = Announcement.builder()
                .id(10L)
                .title("考场纪律须知")
                .summary("导读摘要")
                .content("# 正文内容")
                .authorName("admin")
                .isPinned(1)
                .priority(50)
                .status("PUBLISHED")
                .viewCount(100)
                .createdBy(1L)
                .createdAt(LocalDateTime.now())
                .build();
        when(mapper.findById(10L)).thenReturn(announcement);

        AnnouncementDetailVO vo = service.getDetail(10L);

        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(10L);
        assertThat(vo.getViewCount()).isEqualTo(101);
        verify(mapper).incrementViewCount(10L);
    }

    @Test
    @DisplayName("公告详情：草稿或已下架公告前台访问抛出 4004 异常")
    void getDetail_notPublished_throwsNotFound() {
        Announcement draft = Announcement.builder()
                .id(11L)
                .title("未发布公告")
                .status("DRAFT")
                .build();
        when(mapper.findById(11L)).thenReturn(draft);

        assertThatThrownBy(() -> service.getDetail(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在或未发布");

        verify(mapper, never()).incrementViewCount(anyLong());
    }

    @Test
    @DisplayName("发布公告：未填摘要时自动截取前100字纯文本，清理缓存")
    void create_autoSummaryAndCacheEviction() {
        AuthContextHolder.set(new AuthUser(2L, "teacher_zhang", Set.of(Role.TEACHER.name())));

        String longMarkdown = "## 重要通知\n" +
                "欢迎参加 **KECE 全真机考**！请大家在考试前登录系统检查个人信息与网络连接。\n" +
                "```python\nprint('hello world')\n```\n" +
                "本次考试包含算法题、客观题以及 SQL 数据库上机测试。请严格遵守考场纪律，禁止任何形式的作弊行为。\n" +
                "如遇设备故障请立刻举手示意监考教师处理。祝大家考试顺利并取得优异成绩！更多详情请参考相关操作手册。";

        AnnouncementRequest req = AnnouncementRequest.builder()
                .title("全真机考考前通知")
                .content(longMarkdown)
                .isPinned(1)
                .priority(80)
                .status("PUBLISHED")
                .build();

        when(redis.keys("notice:board:*")).thenReturn(Set.of("notice:board:page:1:10"));

        AnnouncementDetailVO vo = service.create(req);

        ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);
        verify(mapper).insert(captor.capture());

        Announcement saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("全真机考考前通知");
        assertThat(saved.getAuthorName()).isEqualTo("teacher_zhang");
        assertThat(saved.getCreatedBy()).isEqualTo(2L);
        assertThat(saved.getSummary()).doesNotContain("##").doesNotContain("```");
        assertThat(saved.getSummary().length()).isLessThanOrEqualTo(100);

        verify(redis).delete(Set.of("notice:board:page:1:10"));
    }

    @Test
    @DisplayName("置顶切换：未传 isPinned 时自动反转置顶状态")
    void updatePinned_toggle() {
        Announcement existing = Announcement.builder()
                .id(5L)
                .title("测试公告")
                .isPinned(1)
                .status("PUBLISHED")
                .build();
        when(mapper.findById(5L)).thenReturn(existing);
        when(redis.keys("notice:board:*")).thenReturn(Set.of("notice:board:page:1:10"));

        AnnouncementDetailVO vo = service.updatePinned(5L, null);

        assertThat(vo.getIsPinned()).isEqualTo(0);
        verify(mapper).updatePinned(5L, 0);
        verify(redis).delete(any(Set.class));
    }

    @Test
    @DisplayName("状态修改与下架：同步清理看板缓存")
    void updateStatus_evictCache() {
        Announcement existing = Announcement.builder()
                .id(6L)
                .title("待下架公告")
                .status("PUBLISHED")
                .build();
        when(mapper.findById(6L)).thenReturn(existing);
        when(redis.keys("notice:board:*")).thenReturn(Set.of("notice:board:page:1:10"));

        AnnouncementDetailVO vo = service.updateStatus(6L, "ARCHIVED");

        assertThat(vo.getStatus()).isEqualTo("ARCHIVED");
        verify(mapper).updateStatus(6L, "ARCHIVED");
        verify(redis).delete(any(Set.class));
    }

    @Test
    @DisplayName("删除公告：存在则删除并清理看板缓存")
    void delete_success() {
        Announcement existing = Announcement.builder()
                .id(7L)
                .title("废弃公告")
                .status("ARCHIVED")
                .build();
        when(mapper.findById(7L)).thenReturn(existing);
        when(redis.keys("notice:board:*")).thenReturn(Set.of("notice:board:page:1:10"));

        service.delete(7L);

        verify(mapper).deleteById(7L);
        verify(redis).delete(any(Set.class));
    }

    @Test
    @DisplayName("纯文本摘要提取：精确过滤特殊符号与代码块")
    void extractSummary_markdownFiltering() {
        String md = "### 标题\n- 列表项1\n- 列表项2\n```sql\nSELECT 1;\n```\n[点击链接](https://kece.cn) **加粗文字**";
        String summary = AnnouncementServiceImpl.extractSummary(md);

        assertThat(summary).doesNotContain("###");
        assertThat(summary).doesNotContain("SELECT 1;");
        assertThat(summary).contains("标题");
        assertThat(summary).contains("点击链接");
        assertThat(summary).contains("加粗文字");
    }
}
