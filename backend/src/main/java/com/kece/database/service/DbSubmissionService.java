package com.kece.database.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.admin.dto.RejudgeResultVO;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageParam;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.database.dto.DbSubmissionListVO;
import com.kece.database.entity.DbQuestion;
import com.kece.database.entity.DbSubmission;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.database.mapper.DbTestCaseMapper;
import com.kece.judging.JudgeDispatch;
import com.kece.judging.JudgeDispatch.Scene;
import com.kece.practice.service.LessonPracticeService;
import com.kece.submission.dto.SubmissionResultVO;
import com.kece.submission.dto.SubmissionVO;
import com.kece.submission.enums.SubmissionStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 数据库提交与评测业务逻辑：创建持久判题任务、状态进度读取与结果查询。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbSubmissionService {

    private final DbSubmissionMapper submissionMapper;
    private final DbQuestionMapper questionMapper;
    private final DbTestCaseMapper testCaseMapper;
    private final JudgeDispatch judging;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ObjectProvider<LessonPracticeService> practiceServiceProvider;

    /** 兼容旧签名的提交方法。 */
    @Transactional
    public SubmissionVO submit(Long questionId, String sql) {
        return submit(questionId, sql, null, null);
    }

    /** 提交 SQL：入库、原子关联随堂课练（若带上下文）、创建持久判题任务，返回任务 ID 与 PENDING。 */
    @Transactional
    public SubmissionVO submit(
            Long questionId, String sql, String practiceCode, Long practiceQuestionId) {
        Long userId = AuthContextHolder.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "SQL 不能为空");
        }
        DbQuestion question = questionMapper.findById(questionId);
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在或未公开");
        }
        if (question.getStatus() == null || question.getStatus() != 1) {
            AuthUser authUser = AuthContextHolder.get();
            boolean privileged =
                    authUser != null && isPrivilegedForQuestion(authUser, question.getId());
            if (!privileged && !canAccessViaPractice(practiceCode, userId, questionId)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在或未公开");
            }
        }

        DbSubmission submission = new DbSubmission();
        submission.setUserId(userId);
        submission.setQuestionId(questionId);
        submission.setDialect(question.getDialect());
        submission.setSqlContent(trimmed);
        submission.setStatus(SubmissionStatus.PENDING.name());
        submissionMapper.insert(submission);

        // 在创建持久判题任务之前同一事务内原子绑定课练记录
        boolean practiceBound =
                bindPracticeSubmissionIfPresent(
                        practiceCode, userId, practiceQuestionId, questionId, submission.getId());

        return judging.enqueueSql(submission, practiceBound ? Scene.PRACTICE : Scene.PROBLEM);
    }

    private boolean canAccessViaPractice(String practiceCode, Long userId, Long questionId) {
        if (practiceCode == null || practiceCode.isBlank() || practiceServiceProvider == null) {
            return false;
        }
        LessonPracticeService practiceService = practiceServiceProvider.getIfAvailable();
        return practiceService != null
                && practiceService.canUserAccessQuestionViaPractice(
                        practiceCode, userId, "DB", questionId);
    }

    private boolean bindPracticeSubmissionIfPresent(
            String practiceCode,
            Long userId,
            Long practiceQuestionId,
            Long questionId,
            Long submissionId) {
        if (practiceCode == null
                || practiceCode.isBlank()
                || practiceQuestionId == null
                || practiceServiceProvider == null) {
            return false;
        }
        LessonPracticeService practiceService = practiceServiceProvider.getIfAvailable();
        return practiceService != null
                && practiceService.bindPracticalSubmissionOnCreate(
                        practiceCode, userId, practiceQuestionId, questionId, submissionId, "DB");
    }

    /** 数据库提交记录列表：默认本人提交，可指定 targetUserId 查看该用户提交（非本人自动脱敏 SQL 原文），按 id 倒序分页。 */
    public PageResult<DbSubmissionListVO> list(
            Long targetUserId, String status, int page, int size) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = targetUserId != null ? targetUserId : user.getId();
        PageParam p = PageParam.of(page, size);
        String statusFilter =
                (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        List<DbSubmissionListVO> list =
                submissionMapper.findPage(userId, null, statusFilter, null, p.offset(), p.size());
        // 安全防抄袭：前台数据库提交列表仅提交者本人可见 SQL 原文，其他人一律脱敏置空
        if (!userId.equals(user.getId())) {
            for (DbSubmissionListVO item : list) {
                item.setSqlContent(null);
            }
        }
        long total = submissionMapper.count(userId, null, statusFilter, null);
        return new PageResult<>(list, total, p.page(), p.size());
    }

    public PageResult<DbSubmissionListVO> list(String status, int page, int size) {
        return list(null, status, page, size);
    }

    /** 后台数据库提交列表：ADMIN 可跨用户/跨题查询；TEACHER 仅限自己所出题目的提交（仍可筛任意用户，归属约束在 SQL createdBy 条件）。 */
    public PageResult<DbSubmissionListVO> listAdmin(
            String status, Long userId, Long questionId, int page, int size) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        PageParam p = PageParam.of(page, size);
        String statusFilter =
                (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        Long targetCreatedBy = user.hasRole(Role.ADMIN) ? null : user.getId();
        List<DbSubmissionListVO> list =
                submissionMapper.findPage(
                        userId, questionId, statusFilter, targetCreatedBy, p.offset(), p.size());
        long total = submissionMapper.count(userId, questionId, statusFilter, targetCreatedBy);
        return new PageResult<>(list, total, p.page(), p.size());
    }

    /** 当前用户已 AC 的数据库题目 id（去重），供题目列表标记「已通过」。 */
    public List<Long> solvedQuestionIds() {
        Long userId = AuthContextHolder.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return submissionMapper.findAcceptedQuestionIds(userId);
    }

    /** 查询评测状态与结果：通过当前任务指针聚合持久任务实时进度，历史无指针记录直接读取数据库终态。 */
    public SubmissionVO get(Long id) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        DbSubmission submission = submissionMapper.findStatusById(id);
        if (submission == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "提交记录不存在");
        }
        if (!canAccessSubmission(user, submission)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        SubmissionVO vo = toVOFromEntity(submission);
        if (submission.getCurrentJudgeJobId() != null) {
            vo = judging.persistentStatus(submission.getCurrentJudgeJobId(), vo);
        }
        return sanitize(vo, user, submission.getQuestionId());
    }

    /** 样例豁免统一（决策 27）：样例测试点的数据本就随题面公开，其比对结果始终可见；非样例仅管理员或该题出题人可见， 其余答题者置空，避免由比对结果反推隐藏测试点的数据与答案。 */
    private SubmissionVO sanitize(SubmissionVO vo, AuthUser user, Long questionId) {
        if (vo == null || vo.getResult() == null) {
            return vo;
        }
        var report = vo.getResult().getTestCasesReport();
        if (report == null || report.isEmpty()) {
            return vo;
        }
        boolean privileged = isPrivilegedForQuestion(user, questionId);
        Set<Long> sampleCaseIds = findSampleCaseIds(questionId);
        report.forEach(
                c -> {
                    if (sampleCaseIds.contains(c.getCaseId()) || privileged) {
                        return;
                    }
                    c.setExpectedOutput(null);
                    c.setActualOutput(null);
                });
        return vo;
    }

    /** 管理员或该题出题人可见隐藏用例。 */
    private boolean isPrivilegedForQuestion(AuthUser user, Long questionId) {
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        DbQuestion question = questionId == null ? null : questionMapper.findById(questionId);
        return question != null && question.getCreatedBy().equals(user.getId());
    }

    /** 重判单条数据库提交：仅管理员或该题出题人可操作；评测中的提交拒绝；在行锁内重置结果并递增持久任务代次。 */
    @Transactional
    public RejudgeResultVO rejudge(Long id) {
        DbSubmission submission = submissionMapper.findByIdForUpdate(id);
        if (submission == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "提交记录不存在");
        }
        checkRejudgePermission(submission);
        if (isJudging(submission.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该提交正在评测中，请稍后再试");
        }
        DbQuestion question = questionMapper.findById(submission.getQuestionId());
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在或已删除");
        }

        submissionMapper.resetResult(id);
        judging.enqueueSql(submission, Scene.REJUDGE);
        return RejudgeResultVO.builder()
                .submissionId(id)
                .status(SubmissionStatus.PENDING.name())
                .build();
    }

    private void checkRejudgePermission(DbSubmission submission) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (user.hasRole(Role.ADMIN)) {
            return;
        }
        DbQuestion question = questionMapper.findById(submission.getQuestionId());
        if (question == null || !question.getCreatedBy().equals(user.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    /** 评测中（非终态中的进行态）不允许重判。 */
    private boolean isJudging(String status) {
        return "PENDING".equals(status) || "COMPILING".equals(status) || "JUDGING".equals(status);
    }

    /** 该提交所属题目的样例测试点 id；题目已删除或查不到时返回空集合（等价于全部隐藏）。 */
    private Set<Long> findSampleCaseIds(Long questionId) {
        if (questionId == null) {
            return Set.of();
        }
        List<Long> ids = testCaseMapper.findSampleCaseIds(questionId);
        return ids == null || ids.isEmpty() ? Set.of() : new HashSet<>(ids);
    }

    private SubmissionVO toVOFromEntity(DbSubmission s) {
        SubmissionResultVO result = null;
        if (s.getResultJson() != null && !s.getResultJson().isBlank()) {
            result = readJson(s.getResultJson(), SubmissionResultVO.class);
        }
        return SubmissionVO.builder()
                .submissionId(s.getId())
                .status(s.getStatus())
                .result(result)
                .build();
    }

    /** 提交可见性：管理员、提交人本人、该题出题人均可查看。 */
    private boolean canAccessSubmission(AuthUser user, DbSubmission submission) {
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        if (submission.getUserId().equals(user.getId())) {
            return true;
        }
        DbQuestion question = questionMapper.findById(submission.getQuestionId());
        return question != null && question.getCreatedBy().equals(user.getId());
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
