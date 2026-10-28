package com.kece.common.config;

import com.kece.database.entity.DbQuestion;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.objective.entity.ObjectiveQuestion;
import com.kece.objective.mapper.ObjectiveQuestionMapper;
import com.kece.problemset.entity.ProblemSet;
import com.kece.problemset.entity.ProblemSetItem;
import com.kece.problemset.entity.ProblemSetSection;
import com.kece.problemset.mapper.ProblemSetItemMapper;
import com.kece.problemset.mapper.ProblemSetMapper;
import com.kece.problemset.mapper.ProblemSetSectionMapper;
import com.kece.question.entity.Question;
import com.kece.question.mapper.QuestionMapper;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 官方精选题集初始化播种器：每个题集严格限定为单一专属题型（ALGO / DB / OBJECTIVE），
 * 在算法、数据库、客观题播种完成后运行（{@code @Order(10)}），按标题幂等防重。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ProblemSetSeeder {

    private final ProblemSetMapper problemSetMapper;
    private final ProblemSetSectionMapper sectionMapper;
    private final ProblemSetItemMapper itemMapper;
    private final QuestionMapper questionMapper;
    private final DbQuestionMapper dbQuestionMapper;
    private final ObjectiveQuestionMapper objectiveQuestionMapper;
    private final UserMapper userMapper;

    @Order(10)
    @Bean
    public ApplicationRunner seedOfficialProblemSets() {
        return args -> {
            try {
                User admin = userMapper.findByUsername("admin");
                User teacher = userMapper.findByUsername("teacher");
                Long authorId =
                        admin != null ? admin.getId() : (teacher != null ? teacher.getId() : 1L);

                List<Question> algoList =
                        questionMapper.findPage(null, null, null, 1, null, null, null, 0, 20);
                List<DbQuestion> dbList =
                        dbQuestionMapper.findPage(null, null, 1, null, null, null, 0, 20);
                List<ObjectiveQuestion> objList =
                        objectiveQuestionMapper.findPage(null, null, 1, null, 0, 20);

                seedAlgoSet(authorId, algoList);
                seedDbSet(authorId, dbList);
                seedObjectiveSet(authorId, objList);
            } catch (Exception e) {
                log.warn("官方精选题集初始化跳过或异常: {}", e.getMessage());
            }
        };
    }

    private void seedAlgoSet(Long authorId, List<Question> algoList) {
        String title = "算法与数据结构核心突击";
        if (problemSetMapper.findByTitle(title) != null || algoList == null || algoList.isEmpty()) {
            return;
        }
        ProblemSet ps =
                ProblemSet.builder()
                        .title(title)
                        .subtitle("基础语法 · 双指针 · 动态规划与图论模板")
                        .description("精选高频算法编程真题，从基础输入输出与哈希模拟过渡到核心搜索与动态规划，适合日常巩固与机考热身。")
                        .category("ALGO")
                        .difficulty("MEDIUM")
                        .tags("算法基础,高频机考,数据结构")
                        .itemCount(algoList.size())
                        .algoCount(algoList.size())
                        .dbCount(0)
                        .objectiveCount(0)
                        .participantCount(12)
                        .isOfficial(1)
                        .status(1)
                        .version(1)
                        .createdBy(authorId)
                        .build();
        problemSetMapper.insert(ps);

        ProblemSetSection s1 =
                ProblemSetSection.builder()
                        .setId(ps.getId())
                        .title("第一章 · 基础算法与线性结构")
                        .description("掌握标准输入输出、数组遍历与哈希查找")
                        .sortOrder(1)
                        .build();
        sectionMapper.insert(s1);

        ProblemSetSection s2 =
                ProblemSetSection.builder()
                        .setId(ps.getId())
                        .title("第二章 · 进阶思维与综合实战")
                        .description("覆盖递归、动态规划与复杂边界条件处理")
                        .sortOrder(2)
                        .build();
        sectionMapper.insert(s2);

        List<ProblemSetItem> items = new ArrayList<>();
        int mid = Math.max(1, (algoList.size() + 1) / 2);
        for (int i = 0; i < algoList.size(); i++) {
            Question q = algoList.get(i);
            items.add(
                    ProblemSetItem.builder()
                            .setId(ps.getId())
                            .sectionId(i < mid ? s1.getId() : s2.getId())
                            .questionType("ALGO")
                            .questionId(q.getId())
                            .titleSnapshot(q.getTitle())
                            .difficultySnapshot(q.getDifficulty())
                            .sourceAlive(1)
                            .sortOrder(i + 1)
                            .build());
        }
        if (!items.isEmpty()) {
            itemMapper.insertBatch(items);
        }
        log.info("已播种官方算法题集: {} ({} 题)", title, items.size());
    }

    private void seedDbSet(Long authorId, List<DbQuestion> dbList) {
        String title = "MySQL 8.0 实战查询与数据操纵进阶";
        if (problemSetMapper.findByTitle(title) != null || dbList == null || dbList.isEmpty()) {
            return;
        }
        ProblemSet ps =
                ProblemSet.builder()
                        .title(title)
                        .subtitle("多表关联 · 聚合窗口 · DML 事务变更")
                        .description("基于物理隔离临时库对拍，系统训练 SELECT 复杂检索、GROUP BY 分组过滤与 DML 数据订正实战能力。")
                        .category("DB")
                        .difficulty("MEDIUM")
                        .tags("MySQL 8.0,SQL检索,DML事务")
                        .itemCount(dbList.size())
                        .algoCount(0)
                        .dbCount(dbList.size())
                        .objectiveCount(0)
                        .participantCount(9)
                        .isOfficial(1)
                        .status(1)
                        .version(1)
                        .createdBy(authorId)
                        .build();
        problemSetMapper.insert(ps);

        ProblemSetSection s1 =
                ProblemSetSection.builder()
                        .setId(ps.getId())
                        .title("第一部分 · SELECT 检索与聚合统计")
                        .description("单表过滤、多表 JOIN 与分组汇总")
                        .sortOrder(1)
                        .build();
        sectionMapper.insert(s1);

        List<ProblemSetItem> items = new ArrayList<>();
        for (int i = 0; i < dbList.size(); i++) {
            DbQuestion q = dbList.get(i);
            items.add(
                    ProblemSetItem.builder()
                            .setId(ps.getId())
                            .sectionId(s1.getId())
                            .questionType("DB")
                            .questionId(q.getId())
                            .titleSnapshot(q.getTitle())
                            .difficultySnapshot(q.getType())
                            .sourceAlive(1)
                            .sortOrder(i + 1)
                            .build());
        }
        if (!items.isEmpty()) {
            itemMapper.insertBatch(items);
        }
        log.info("已播种官方数据库题集: {} ({} 题)", title, items.size());
    }

    private void seedObjectiveSet(Long authorId, List<ObjectiveQuestion> objList) {
        String title = "计算机核心理论客观题专题";
        if (problemSetMapper.findByTitle(title) != null || objList == null || objList.isEmpty()) {
            return;
        }

        ProblemSet ps =
                ProblemSet.builder()
                        .title(title)
                        .subtitle("单选 · 多选 · 判断与填空专项巩固")
                        .description("覆盖操作系统、计算机网络、数据结构与数据库原理高频考点客观题，支持即时判分与解析反馈。")
                        .category("OBJECTIVE")
                        .difficulty("MEDIUM")
                        .tags("理论基础,期末复习,客观题专项")
                        .itemCount(objList.size())
                        .algoCount(0)
                        .dbCount(0)
                        .objectiveCount(objList.size())
                        .participantCount(15)
                        .isOfficial(1)
                        .status(1)
                        .version(1)
                        .createdBy(authorId)
                        .build();
        problemSetMapper.insert(ps);

        ProblemSetSection sObj =
                ProblemSetSection.builder()
                        .setId(ps.getId())
                        .title("第一部分 · 核心理论考点")
                        .description("夯实计算机基础知识与概念辨析")
                        .sortOrder(1)
                        .build();
        sectionMapper.insert(sObj);

        List<ProblemSetItem> items = new ArrayList<>();
        int order = 1;
        for (ObjectiveQuestion q : objList) {
            String cleanTitle =
                    q.getContent() != null
                            ? q.getContent().replaceAll("\\s+", " ").trim()
                            : ("客观题 #" + q.getId());
            if (cleanTitle.length() > 48) {
                cleanTitle = cleanTitle.substring(0, 48) + "…";
            }
            items.add(
                    ProblemSetItem.builder()
                            .setId(ps.getId())
                            .sectionId(sObj.getId())
                            .questionType("OBJECTIVE")
                            .questionId(q.getId())
                            .titleSnapshot(cleanTitle)
                            .difficultySnapshot(q.getType())
                            .sourceAlive(1)
                            .sortOrder(order++)
                            .build());
        }
        if (!items.isEmpty()) {
            itemMapper.insertBatch(items);
        }
        log.info("已播种官方客观题集: {} ({} 题)", title, items.size());
    }
}
