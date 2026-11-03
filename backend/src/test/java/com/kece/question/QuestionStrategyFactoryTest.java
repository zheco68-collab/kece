package com.kece.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.objective.mapper.ObjectiveQuestionMapper;
import com.kece.question.mapper.QuestionMapper;
import com.kece.question.strategy.*;
import com.kece.submission.mapper.SubmissionMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 题目类型策略工厂与多态策略单元测试。 */
class QuestionStrategyFactoryTest {

    private QuestionStrategyFactory factory;
    private AlgoQuestionStrategy algoStrategy;
    private DbQuestionStrategy dbStrategy;
    private ObjectiveQuestionStrategy objectiveStrategy;
    private FrontendQuestionStrategy frontendStrategy;

    @BeforeEach
    void setUp() {
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        SubmissionMapper submissionMapper = mock(SubmissionMapper.class);
        DbQuestionMapper dbQuestionMapper = mock(DbQuestionMapper.class);
        DbSubmissionMapper dbSubmissionMapper = mock(DbSubmissionMapper.class);
        ObjectiveQuestionMapper objectiveQuestionMapper = mock(ObjectiveQuestionMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();

        algoStrategy =
                new AlgoQuestionStrategy(questionMapper, submissionMapper, redis, objectMapper);
        dbStrategy =
                new DbQuestionStrategy(dbQuestionMapper, dbSubmissionMapper, redis, objectMapper);
        objectiveStrategy = new ObjectiveQuestionStrategy(objectiveQuestionMapper, objectMapper);
        frontendStrategy = new FrontendQuestionStrategy(redis, objectMapper);

        factory =
                new QuestionStrategyFactory(
                        List.of(algoStrategy, dbStrategy, objectiveStrategy, frontendStrategy));
    }

    @Test
    void testStrategyResolution_caseInsensitive() {
        assertThat(factory.getStrategy("ALGO")).isSameAs(algoStrategy);
        assertThat(factory.getStrategy("algo")).isSameAs(algoStrategy);
        assertThat(factory.getStrategy("DB")).isSameAs(dbStrategy);
        assertThat(factory.getStrategy("db")).isSameAs(dbStrategy);
        assertThat(factory.getStrategy("OBJECTIVE")).isSameAs(objectiveStrategy);
        assertThat(factory.getStrategy("objective")).isSameAs(objectiveStrategy);
        assertThat(factory.getStrategy("FRONTEND")).isSameAs(frontendStrategy);
        assertThat(factory.getStrategy("frontend")).isSameAs(frontendStrategy);
    }

    @Test
    void testPracticalProperty() {
        assertThat(factory.getStrategy("ALGO").isPractical()).isTrue();
        assertThat(factory.getStrategy("DB").isPractical()).isTrue();
        assertThat(factory.getStrategy("FRONTEND").isPractical()).isTrue();
        assertThat(factory.getStrategy("OBJECTIVE").isPractical()).isFalse();
    }

    @Test
    void testSupports() {
        assertThat(factory.supports("ALGO")).isTrue();
        assertThat(factory.supports("db")).isTrue();
        assertThat(factory.supports("frontend")).isTrue();
        assertThat(factory.supports("objective")).isTrue();
        assertThat(factory.supports("UNKNOWN")).isFalse();
        assertThat(factory.supports(null)).isFalse();
        assertThat(factory.supports("")).isFalse();
    }

    @Test
    void testGetStrategy_unsupportedThrowsException() {
        assertThatThrownBy(() -> factory.getStrategy("UNKNOWN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的题型");

        assertThatThrownBy(() -> factory.getStrategy(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("题型不能为空");
    }

    @Test
    void testFindStrategy() {
        assertThat(factory.findStrategy("ALGO")).contains(algoStrategy);
        assertThat(factory.findStrategy("NON_EXISTENT")).isEmpty();
        assertThat(factory.findStrategy(null)).isEmpty();
    }

    @Test
    void testGetAllStrategies() {
        assertThat(factory.getAllStrategies()).hasSize(4);
    }
}
