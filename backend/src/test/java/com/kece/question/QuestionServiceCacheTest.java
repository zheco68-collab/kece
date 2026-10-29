package com.kece.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kece.common.response.PageResult;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.question.dto.QuestionRequest;
import com.kece.question.dto.QuestionVO;
import com.kece.question.entity.Question;
import com.kece.question.mapper.QuestionMapper;
import com.kece.question.mapper.TestCaseMapper;
import com.kece.question.service.QuestionService;
import com.kece.question.service.TestCaseStorageService;
import com.kece.submission.mapper.SubmissionMapper;
import com.kece.user.mapper.UserMapper;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class QuestionServiceCacheTest {

    private QuestionMapper questionMapper;
    private TestCaseMapper testCaseMapper;
    private SubmissionMapper submissionMapper;
    private UserMapper userMapper;
    private TestCaseStorageService testCaseStorageService;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private ObjectMapper objectMapper;
    private QuestionService questionService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        questionMapper = mock(QuestionMapper.class);
        testCaseMapper = mock(TestCaseMapper.class);
        submissionMapper = mock(SubmissionMapper.class);
        userMapper = mock(UserMapper.class);
        testCaseStorageService = mock(TestCaseStorageService.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);

        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        questionService =
                new QuestionService(
                        questionMapper,
                        testCaseMapper,
                        submissionMapper,
                        userMapper,
                        testCaseStorageService,
                        redis,
                        objectMapper);
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void list_onCacheMiss_shouldQueryDbAndPopulateCache() {
        when(valueOps.get(anyString())).thenReturn(null);

        Question q = new Question();
        q.setId(101L);
        q.setTitle("测试题目");
        q.setDifficulty("EASY");
        q.setStatus(1);

        when(questionMapper.findPage(any(), any(), any(), eq(1), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(q));
        when(questionMapper.count(any(), any(), any(), eq(1), any(), any(), any()))
                .thenReturn(1L);

        PageResult<QuestionVO> result = questionService.list(null, null, null, null, 1, 20, false);

        assertThat(result).isNotNull();
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getTitle()).isEqualTo("测试题目");

        verify(questionMapper).findPage(any(), any(), any(), eq(1), any(), any(), any(), anyInt(), anyInt());
        verify(valueOps).set(eq("question:cache:list:1:20:::"), anyString(), any(Duration.class));
        verify(setOps).add("question:cache:list_keys", "question:cache:list:1:20:::");
    }

    @Test
    void list_onCacheHit_shouldReturnCachedResultDirectly() throws Exception {
        PageResult<QuestionVO> cachedPage =
                new PageResult<>(
                        List.of(QuestionVO.builder().id(99L).title("缓存题目").difficulty("HARD").build()),
                        1L,
                        1,
                        20);
        String cachedJson = objectMapper.writeValueAsString(cachedPage);

        when(valueOps.get("question:cache:list:1:20:::")).thenReturn(cachedJson);

        PageResult<QuestionVO> result = questionService.list(null, null, null, null, 1, 20, false);

        assertThat(result).isNotNull();
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getTitle()).isEqualTo("缓存题目");

        // DB 必须未被访问
        verify(questionMapper, never()).findPage(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void create_shouldEvictListKeysAndTagsAndDetail() {
        AuthUser admin = new AuthUser(1L, "admin", Set.of(Role.ADMIN.name()));
        AuthContextHolder.set(admin);

        when(setOps.members("question:cache:list_keys"))
                .thenReturn(Set.of("question:cache:list:1:20:::", "question:cache:list:2:20:::"));

        QuestionRequest req = new QuestionRequest();
        req.setTitle("新题目");
        req.setDescription("描述");
        req.setDifficulty("EASY");
        req.setTimeLimit(1000);
        req.setMemoryLimit(256);
        req.setLanguages("cpp");

        QuestionVO created = questionService.create(req);

        assertThat(created).isNotNull();
        verify(questionMapper).insert(any(Question.class));
        // 验证批量删除所有列表缓存键与跟踪 set
        verify(redis).delete(Set.of("question:cache:list:1:20:::", "question:cache:list:2:20:::"));
        verify(redis).delete("question:cache:list_keys");
        verify(redis).delete("question:cache:tags");
    }

    @Test
    void delete_shouldEvictListAndDetailCache() {
        AuthUser admin = new AuthUser(1L, "admin", Set.of(Role.ADMIN.name()));
        AuthContextHolder.set(admin);

        Question q = new Question();
        q.setId(55L);
        q.setCreatedBy(1L);
        when(questionMapper.findById(55L)).thenReturn(q);
        when(submissionMapper.existsByQuestionId(55L)).thenReturn(false);
        when(setOps.members("question:cache:list_keys")).thenReturn(Set.of("question:cache:list:1:20:::"));

        questionService.delete(55L);

        verify(questionMapper).deleteById(55L);
        verify(redis).delete("question:cache:detail:55");
        verify(redis).delete(Set.of("question:cache:list:1:20:::"));
    }
}
