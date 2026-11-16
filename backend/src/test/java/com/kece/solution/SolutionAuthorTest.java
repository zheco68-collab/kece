package com.kece.solution;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kece.common.exception.GlobalExceptionHandler;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.solution.controller.SolutionController;
import com.kece.solution.dto.SolutionQuestionVO;
import com.kece.solution.entity.Solution;
import com.kece.solution.mapper.SolutionMapper;
import com.kece.solution.mapper.SolutionQuestionMapper;
import com.kece.solution.service.SolutionService;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SolutionAuthorTest {

    private SolutionMapper solutionMapper;
    private SolutionQuestionMapper questionMapper;
    private UserMapper userMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        solutionMapper = mock(SolutionMapper.class);
        questionMapper = mock(SolutionQuestionMapper.class);
        userMapper = mock(UserMapper.class);
        SolutionService service = new SolutionService(solutionMapper, questionMapper, userMapper);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new SolutionController(service))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void listByAuthor_returnsPublicSolutionsEvenForAdminViewer() throws Exception {
        AuthContextHolder.set(new AuthUser(1L, "admin", Set.of("ADMIN")));

        Solution s1 = new Solution();
        s1.setId(101L);
        s1.setQuestionType("ALGO");
        s1.setQuestionId(5L);
        s1.setAuthorId(28L);
        s1.setTitle("线性扫一遍");
        s1.setStatus("PUBLISHED");
        s1.setVersion(1L);
        s1.setCreatedAt(LocalDateTime.of(2026, 11, 10, 12, 0));
        s1.setUpdatedAt(LocalDateTime.of(2026, 11, 10, 12, 30));

        User bob = new User();
        bob.setId(28L);
        bob.setUsername("bob");

        SolutionQuestionVO algoQ = new SolutionQuestionVO();
        algoQ.setId(5L);
        algoQ.setTitle("两数之和");
        algoQ.setStatus(1);

        when(solutionMapper.findPublicByAuthorPage(28L, 0, 10)).thenReturn(List.of(s1));
        when(solutionMapper.countPublicByAuthor(28L)).thenReturn(1L);
        when(userMapper.findByIds(Set.of(28L))).thenReturn(List.of(bob));
        when(questionMapper.findAlgoByIds(Set.of(5L))).thenReturn(List.of(algoQ));

        mockMvc.perform(
                        get("/solutions/author")
                                .param("userId", "28")
                                .param("page", "1")
                                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(101))
                .andExpect(jsonPath("$.data.list[0].authorName").value("bob"))
                .andExpect(jsonPath("$.data.list[0].questionTitle").value("两数之和"))
                .andExpect(jsonPath("$.data.list[0].questionPublic").value(true));

        verify(solutionMapper, never()).findMinePage(any(), any(int.class), any(int.class));
    }

    @Test
    void listByAuthor_rejectsNonPositiveUserId() throws Exception {
        mockMvc.perform(get("/solutions/author").param("userId", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }
}
