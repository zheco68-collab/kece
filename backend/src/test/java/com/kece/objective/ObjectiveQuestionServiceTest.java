package com.kece.objective;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.response.PageResult;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.objective.dto.ObjectiveQuestionVO;
import com.kece.objective.entity.ObjectiveQuestion;
import com.kece.objective.mapper.ObjectiveQuestionMapper;
import com.kece.objective.mapper.ObjectiveSubmissionMapper;
import com.kece.objective.service.ObjectiveGrader;
import com.kece.objective.service.ObjectiveQuestionService;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ObjectiveQuestionServiceTest {

    @Mock private ObjectiveQuestionMapper mapper;

    @Mock private ObjectiveSubmissionMapper submissionMapper;

    @Mock private ObjectiveGrader grader;

    @Mock private UserMapper userMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ObjectiveQuestionService service;

    @BeforeEach
    void setUp() {
        service =
                new ObjectiveQuestionService(
                        mapper, submissionMapper, grader, objectMapper, userMapper);
    }

    @Test
    void list_shouldReturnQuestionsWithAuthorInfoAndNullSafety() {
        ObjectiveQuestion q1 = new ObjectiveQuestion();
        q1.setId(1L);
        q1.setType("SINGLE");
        q1.setContent("1+1=?");
        q1.setOptions("[\"A. 1\",\"B. 2\"]");
        q1.setAnswer("B");
        q1.setCreatedBy(100L);
        q1.setStatus(1);
        q1.setCreatedAt(LocalDateTime.now());

        ObjectiveQuestion q2 = new ObjectiveQuestion();
        q2.setId(2L);
        q2.setType("JUDGE");
        q2.setContent("TCP is reliable");
        q2.setOptions("[\"对\",\"错\"]");
        q2.setAnswer("对");
        q2.setCreatedBy(null); // null author edge case
        q2.setStatus(1);
        q2.setCreatedAt(LocalDateTime.now());

        when(mapper.findPage(isNull(), isNull(), eq(1), isNull(), isNull(), eq(0), eq(10)))
                .thenReturn(List.of(q1, q2));
        when(mapper.count(isNull(), isNull(), eq(1), isNull(), isNull())).thenReturn(2L);

        User author = new User();
        author.setId(100L);
        author.setUsername("teacher_alice");
        author.setAvatarUrl("https://example.com/avatar.png");
        when(userMapper.findById(100L)).thenReturn(author);

        PageResult<ObjectiveQuestionVO> result = service.list(null, null, 1, 10);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getList()).hasSize(2);

        ObjectiveQuestionVO vo1 = result.getList().get(0);
        assertThat(vo1.getId()).isEqualTo(1L);
        assertThat(vo1.getAuthorName()).isEqualTo("teacher_alice");
        assertThat(vo1.getAuthorAvatarUrl()).isEqualTo("https://example.com/avatar.png");
        assertThat(vo1.getOptions()).containsExactly("A. 1", "B. 2");
        assertThat(vo1.getAnswer()).isNull(); // unprivileged answer is masked

        ObjectiveQuestionVO vo2 = result.getList().get(1);
        assertThat(vo2.getId()).isEqualTo(2L);
        assertThat(vo2.getAuthorName()).isNull();
        assertThat(vo2.getCreatedBy()).isNull();
        assertThat(vo2.getOptions()).containsExactly("对", "错");
    }

    @Test
    void listAdmin_byId_shouldKeepTeacherOwnershipFilter() {
        AuthContextHolder.set(new AuthUser(7L, "teacher", Set.of(Role.TEACHER.name())));
        try {
            PageResult<ObjectiveQuestionVO> result =
                    service.listAdmin(null, null, null, 99L, 42L, 1, 12);

            assertThat(result.getList()).isEmpty();
            verify(mapper).findPage(null, null, null, 7L, 42L, 0, 12);
            verify(mapper).count(null, null, null, 7L, 42L);
        } finally {
            AuthContextHolder.clear();
        }
    }
}
