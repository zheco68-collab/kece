package com.kece.common.config;

import com.kece.question.entity.Question;
import com.kece.question.entity.TestCase;
import com.kece.question.mapper.QuestionMapper;
import com.kece.question.mapper.TestCaseMapper;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 编程题示例数据播种：首次启动时写入一组经典入门题（含测试用例），便于直接试用与验收。
 *
 * <p>受 {@code kece.demo-data.enabled} 控制（默认关、仅 dev profile 开）；在 {@link DataInitializer} 播种完 teacher
 * 账号后执行（{@code @Order(3)}）。
 *
 * <p>幂等策略：题目按标题精确查重，重复启动不产生重复数据；被出题人改动或删除的题不会被重新写回。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class QuestionSeeder {

    private final QuestionMapper questionMapper;
    private final TestCaseMapper testCaseMapper;
    private final UserMapper userMapper;
    private final com.kece.judging.artifact.JudgeManifestMapper manifestMapper;
    private final com.kece.judging.artifact.JudgeManifestService judgeManifests;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    @Value("${kece.demo-data.enabled:false}")
    private boolean demoDataEnabled;

    @Order(3)
    @Bean
    public ApplicationRunner seedQuestions() {
        return args -> {
            if (!demoDataEnabled) {
                log.info("演示数据开关已关闭（kece.demo-data.enabled=false），跳过编程题示例播种");
                return;
            }
            User teacher = userMapper.findByUsername("teacher");
            if (teacher == null) {
                log.warn("未找到 teacher 账号，跳过编程题示例数据播种");
                return;
            }
            int created = 0;
            for (Seed seed : SEEDS) {
                created += ensureQuestion(seed, teacher.getId());
            }
            if (created > 0) {
                log.info("已播种 {} 道编程题示例", created);
            }
        };
    }

    private int ensureQuestion(Seed seed, Long authorId) {
        Question existing = questionMapper.findByTitle(seed.title());
        if (existing != null) {
            if (manifestMapper.current(
                            com.kece.judging.JudgeDispatch.Kind.ALGO.name(), existing.getId())
                    == null) {
                transaction.executeWithoutResult(
                        tx ->
                                judgeManifests.publish(
                                        com.kece.judging.JudgeDispatch.Kind.ALGO,
                                        existing.getId()));
            }
            return 0;
        }
        return transaction.execute(
                tx -> {
                    Question question = new Question();
                    question.setTitle(seed.title());
                    question.setDescription(seed.description());
                    question.setDifficulty(seed.difficulty());
                    question.setTimeLimit(seed.timeLimitMs());
                    question.setMemoryLimit(256);
                    question.setLanguages("cpp,java,python");
                    question.setCreatedBy(authorId);
                    question.setStatus(1); // 示例题一律公开
                    questionMapper.insert(question);
                    int order = 0;
                    for (Case c : seed.cases()) {
                        TestCase testCase = new TestCase();
                        testCase.setQuestionId(question.getId());
                        testCase.setCaseNum(++order);
                        testCase.setInput(c.input());
                        testCase.setExpectedOutput(c.expectedOutput());
                        testCase.setIsSample(c.sample() ? 1 : 0);
                        testCaseMapper.insert(testCase);
                    }
                    judgeManifests.publish(
                            com.kece.judging.JudgeDispatch.Kind.ALGO, question.getId());
                    return 1;
                });
    }

    /** 标题为查重键，务必保持稳定；改动标题等于新增一道题。 */
    private record Seed(
            String title,
            String description,
            String difficulty,
            int timeLimitMs,
            List<Case> cases) {}

    private record Case(String input, String expectedOutput, boolean sample) {}

    private static final List<Seed> SEEDS =
            List.of(
                    new Seed(
                            "A+B 问题",
                            """
                            ## 题目描述

                            给定两个整数 `a` 和 `b`，输出它们的和。

                            ## 输入格式

                            一行，两个整数 `a` 和 `b`，以空格分隔（$-10^9 \\le a, b \\le 10^9$）。

                            ## 输出格式

                            一行一个整数，表示 `a + b` 的值。
                            """,
                            "EASY",
                            1000,
                            List.of(
                                    new Case("3 5", "8", true),
                                    new Case("-1 1", "0", true),
                                    new Case("1000000000 2000000000", "3000000000", false))),
                    new Seed(
                            "反转字符串",
                            """
                            ## 题目描述

                            给定一个仅含可见 ASCII 字符的字符串，将其反转后输出。

                            ## 输入格式

                            一行一个字符串 `s`（$1 \\le |s| \\le 1000$）。

                            ## 输出格式

                            一行一个字符串，表示反转后的 `s`。
                            """,
                            "EASY",
                            1000,
                            List.of(
                                    new Case("hello", "olleh", true),
                                    new Case("abc123", "321cba", false))),
                    new Seed(
                            "斐波那契数",
                            """
                            ## 题目描述

                            斐波那契数列定义为：$F(0) = 0$，$F(1) = 1$，$F(n) = F(n-1) + F(n-2)$（$n \\ge 2$）。

                            给定整数 `n`，输出 $F(n)$。

                            ## 输入格式

                            一行一个整数 `n`（$0 \\le n \\le 30$）。

                            ## 输出格式

                            一行一个整数，表示 $F(n)$。
                            """,
                            "EASY",
                            1000,
                            List.of(
                                    new Case("2", "1", true),
                                    new Case("10", "55", true),
                                    new Case("30", "832040", false))),
                    new Seed(
                            "判断回文串",
                            """
                            ## 题目描述

                            给定一个仅由字母和数字组成的字符串，判断它是否为回文串（正读与反读完全一致，**区分大小写**）。
                            是则输出 `YES`，否则输出 `NO`。

                            ## 输入格式

                            一行一个字符串 `s`（$1 \\le |s| \\le 1000$）。

                            ## 输出格式

                            一行一个字符串：`YES` 或 `NO`。
                            """,
                            "MEDIUM",
                            1000,
                            List.of(
                                    new Case("level", "YES", true),
                                    new Case("hello", "NO", true),
                                    new Case("Aba", "NO", false))));
}
