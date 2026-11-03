package com.kece.question.strategy;

import com.kece.exam.dto.ExamSessionQuestionVO;
import com.kece.practice.dto.PracticePracticalQuestionVO;
import java.util.Optional;

/** 多态题型策略接口：统一抽象不同题型（算法编程、SQL数据库、客观题、前端实操等）在创建校验、考场装配、课练展示、评测提取及结果聚合中的业务多态行为。 */
public interface QuestionTypeStrategy {

    /** 题型唯一标识符代码（如 "ALGO", "DB", "OBJECTIVE", "FRONTEND" 等）。 */
    String getTypeCode();

    /** 题型人类可读展示名称。 */
    String getDisplayName();

    /** 是否为实战异步评测题型。 算法编程、数据库SQL、前端实操等需要沙箱异步判分的为 true；客观选择填空题同步判分的为 false。 */
    boolean isPractical();

    /**
     * 校验题目存在性及所属创建者，返回题目核心元数据（标题、描述、创建者等）。
     *
     * @param questionId 题目在实体表中的主键ID
     * @return 题目元数据
     */
    QuestionMeta resolveQuestionMeta(Long questionId);

    /**
     * 填充考试中进入考场的题目快照与交互题面（支持题目详情、限制配置、代码/模式骨架）。
     *
     * @param questionId 题目主键ID
     * @param builder 考场题目视图 Builder
     */
    void populateExamSessionQuestion(
            Long questionId, ExamSessionQuestionVO.ExamSessionQuestionVOBuilder builder);

    /**
     * 构造随堂课练中的实战题目视图模型（仅实操题使用）。
     *
     * @param questionId 题目主键ID
     * @param practiceQuestionId 课练挂载的题目快照条目ID
     * @param score 该题在课练中的分值
     * @param sortOrder 题目序号
     * @param userStatus 学生作答状态
     * @param userScore 学生当前得分
     * @param submissionId 最近评测提交ID
     * @return 实战题目视图对象（客观题返回空 Optional）
     */
    default Optional<PracticePracticalQuestionVO> buildPracticePracticalVO(
            Long questionId,
            Long practiceQuestionId,
            Integer score,
            Integer sortOrder,
            String userStatus,
            Integer userScore,
            Long submissionId) {
        return Optional.empty();
    }

    /**
     * 校验提交记录归属（防止学生端利用越权篡改他人 submissionId 刷分），并返回当前真实评测状态与得分。
     *
     * @param submissionId 评测记录ID
     * @param expectedUserId 预期归属用户ID
     * @param expectedQuestionId 预期归属题目ID
     * @return 评测结果 DTO，若归属不符或不存在则返回 null
     */
    JudgeResultDTO verifyAndExtractJudgeInfo(
            Long submissionId, Long expectedUserId, Long expectedQuestionId);

    /**
     * 提取评测结果：优先 Redis 实时状态缓存，回退 MySQL 记录。
     *
     * @param submissionId 评测记录ID
     * @return 评测结果 DTO
     */
    JudgeResultDTO extractJudgeInfo(Long submissionId);

    /**
     * 查找用户在指定题目上的最新提交记录 ID（用于客户端断网恢复与自愈计分）。
     *
     * @param userId 用户ID
     * @param questionId 题目ID
     * @return 最新提交记录ID，无提交则返回 null
     */
    Long findLatestUserSubmissionId(Long userId, Long questionId);

    /** 获取 Redis 评测状态实时缓存 Key。 */
    String getRedisStatusKey(Long submissionId);

    /** 获取 Redis 评测步骤进度实时 Key。 */
    String getRedisProgressKey(Long submissionId);

    /**
     * 获取考试赛制中该用户最新的提交记录（状态及原始分数）。
     *
     * @param examId 考试ID
     * @param userId 考生用户ID
     * @param questionId 题目快照引用的实际题目ID
     * @return 考试提交记录概要，无提交则返回空 Optional
     */
    Optional<ContestSubmissionDTO> findLatestContestSubmission(
            Long examId, Long userId, Long questionId);
}
