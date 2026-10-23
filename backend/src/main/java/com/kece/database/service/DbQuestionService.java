package com.kece.database.service;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.database.dto.DbQuestionRequest;
import com.kece.database.dto.DbQuestionVO;
import com.kece.database.dto.DbTestCaseRequest;
import com.kece.database.dto.DbTestCaseVO;
import com.kece.database.entity.DbQuestion;
import com.kece.database.entity.DbTestCase;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.database.mapper.DbTestCaseMapper;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 数据库题目业务逻辑。 */
@Service
@RequiredArgsConstructor
public class DbQuestionService {

    private final DbQuestionMapper questionMapper;
    private final DbTestCaseMapper testCaseMapper;
    private final DbSubmissionMapper submissionMapper;
    private final UserMapper userMapper;

    public PageResult<DbQuestionVO> list(
            String type, String keyword, int page, int size, boolean all) {
        return list(type, keyword, null, page, size, all);
    }

    public PageResult<DbQuestionVO> list(
            String type, String keyword, String userStatus, int page, int size, boolean all) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        Integer status = (all && isPrivileged()) ? null : 1;

        List<Long> includeIds = null;
        List<Long> excludeIds = null;
        if (userStatus != null && !userStatus.isBlank()) {
            Long userId = AuthContextHolder.currentUserId();
            if (userId == null) {
                if ("SOLVED".equalsIgnoreCase(userStatus)) {
                    return new PageResult<>(List.of(), 0, safePage, safeSize);
                }
            } else {
                List<Long> solvedIds = submissionMapper.findAcceptedQuestionIds(userId);
                if ("SOLVED".equalsIgnoreCase(userStatus)) {
                    if (solvedIds == null || solvedIds.isEmpty()) {
                        return new PageResult<>(List.of(), 0, safePage, safeSize);
                    }
                    includeIds = solvedIds;
                } else if ("UNSOLVED".equalsIgnoreCase(userStatus)) {
                    if (solvedIds != null && !solvedIds.isEmpty()) {
                        excludeIds = solvedIds;
                    }
                }
            }
        }

        List<DbQuestion> items =
                questionMapper.findPage(
                        type, keyword, status, null, includeIds, excludeIds, offset, safeSize);
        long total = questionMapper.count(type, keyword, status, null, includeIds, excludeIds);
        return new PageResult<>(
                items.stream().map(this::toListVO).toList(),
                total,
                safePage,
                safeSize);
    }

    /** 后台数据库题列表：ADMIN 可按 createdBy 筛选，非 ADMIN 仅见自己出的题。 */
    public PageResult<DbQuestionVO> listAdmin(
            String type, String keyword, Integer status, Long createdBy, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        Long targetCreatedBy = createdBy;
        AuthUser user = AuthContextHolder.get();
        if (user == null || !user.hasRole(Role.ADMIN)) {
            targetCreatedBy = AuthContextHolder.currentUserId();
        }
        List<DbQuestion> items =
                questionMapper.findPage(
                        type, keyword, status, targetCreatedBy, null, null, offset, safeSize);
        long total = questionMapper.count(type, keyword, status, targetCreatedBy, null, null);
        return new PageResult<>(
                items.stream().map(this::toListVO).toList(), total, safePage, safeSize);
    }

    public DbQuestionVO detail(Long id) {
        DbQuestion question = getQuestionOrThrow(id);
        if (question.getStatus() == null || question.getStatus() != 1) {
            if (!canManage(question)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在");
            }
        }
        return toVO(question, isPrivileged(), true);
    }

    @Transactional
    public DbQuestionVO create(DbQuestionRequest request) {
        DbQuestion question = new DbQuestion();
        applyRequest(question, request);
        question.setCreatedBy(AuthContextHolder.currentUserId());
        questionMapper.insert(question);
        replaceTestCases(question.getId(), request.getTestCases());
        return toVO(question, true, true);
    }

    @Transactional
    public DbQuestionVO update(Long id, DbQuestionRequest request) {
        DbQuestion question = getQuestionOrThrow(id);
        checkOwnerOrAdmin(question);
        applyRequest(question, request);
        questionMapper.update(question);
        replaceTestCases(question.getId(), request.getTestCases());
        return toVO(question, true, true);
    }

    /** 删除数据库题：无提交记录时出题人/管理员可删；有提交记录时仅 ADMIN 可强制删除（级联清理提交记录）。 */
    @Transactional
    public void delete(Long id) {
        DbQuestion question = getQuestionOrThrow(id);
        checkOwnerOrAdmin(question);
        AuthUser user = AuthContextHolder.get();
        boolean admin = user != null && user.hasRole(Role.ADMIN);
        if (submissionMapper.existsByQuestionId(id) && !admin) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该题目已有提交记录，仅管理员可强制删除");
        }
        if (admin) {
            submissionMapper.deleteByQuestionId(id);
        }
        testCaseMapper.deleteByQuestionId(id);
        questionMapper.deleteById(id);
    }

    private DbQuestion getQuestionOrThrow(Long id) {
        DbQuestion question = questionMapper.findById(id);
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在");
        }
        return question;
    }

    private void checkOwnerOrAdmin(DbQuestion question) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (user.hasRole(Role.ADMIN) || question.getCreatedBy().equals(user.getId())) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private boolean canManage(DbQuestion question) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            return false;
        }
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        return question.getCreatedBy().equals(user.getId());
    }

    private boolean isPrivileged() {
        AuthUser user = AuthContextHolder.get();
        return user != null && (user.hasRole(Role.TEACHER) || user.hasRole(Role.ADMIN));
    }

    private void applyRequest(DbQuestion question, DbQuestionRequest request) {
        question.setTitle(request.getTitle());
        question.setDescription(request.getDescription());
        question.setDialect(
                request.getDialect() == null || request.getDialect().isBlank()
                        ? "MYSQL"
                        : request.getDialect().trim().toUpperCase());
        question.setType(request.getType().trim().toUpperCase());
        question.setSchemaSql(request.getSchemaSql());
        question.setReferenceSql(request.getReferenceSql());
        question.setVerifySql(request.getVerifySql());
        question.setOrderSensitive(request.isOrderSensitive() ? 1 : 0);
        question.setStatus(request.getStatus() == null ? 1 : request.getStatus());
    }

    private void replaceTestCases(Long questionId, List<DbTestCaseRequest> cases) {
        testCaseMapper.deleteByQuestionId(questionId);
        int order = 0;
        for (DbTestCaseRequest c : cases) {
            DbTestCase tc = new DbTestCase();
            tc.setQuestionId(questionId);
            tc.setCaseOrder(order++);
            tc.setSeedSql(c.getSeedSql());
            tc.setExpectedOutput(c.getExpectedOutput());
            tc.setIsSample(c.getIsSample() != null && c.getIsSample() == 1 ? 1 : 0);
            testCaseMapper.insert(tc);
        }
    }

    private DbQuestionVO toListVO(DbQuestion question) {
        return DbQuestionVO.builder()
                .id(question.getId())
                .title(question.getTitle())
                .dialect(question.getDialect())
                .type(question.getType())
                .orderSensitive(
                        question.getOrderSensitive() != null && question.getOrderSensitive() == 1)
                .status(question.getStatus())
                .createdBy(question.getCreatedBy())
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private DbQuestionVO toVO(DbQuestion question, boolean privileged, boolean withCases) {
        List<DbTestCaseVO> caseVOs = withCases ? buildCaseVOs(question.getId(), privileged) : List.of();
        User author = question.getCreatedBy() != null ? userMapper.findById(question.getCreatedBy()) : null;
        return DbQuestionVO.builder()
                .id(question.getId())
                .title(question.getTitle())
                .description(question.getDescription())
                .dialect(question.getDialect())
                .type(question.getType())
                .schemaSql(question.getSchemaSql())
                .referenceSql(privileged ? question.getReferenceSql() : null)
                .verifySql(privileged ? question.getVerifySql() : null)
                .orderSensitive(
                        question.getOrderSensitive() != null && question.getOrderSensitive() == 1)
                .status(question.getStatus())
                .createdBy(question.getCreatedBy())
                .authorName(author != null ? author.getUsername() : null)
                .authorAvatarUrl(author != null ? author.getAvatarUrl() : null)
                .testCases(caseVOs)
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private List<DbTestCaseVO> buildCaseVOs(Long questionId, boolean privileged) {
        List<DbTestCaseVO> result = new ArrayList<>();
        for (DbTestCase tc : testCaseMapper.findByQuestionId(questionId)) {
            boolean canSeeOutput =
                    privileged || (tc.getIsSample() != null && tc.getIsSample() == 1);
            result.add(
                    DbTestCaseVO.builder()
                            .id(tc.getId())
                            .caseOrder(tc.getCaseOrder())
                            .seedSql(tc.getSeedSql())
                            .expectedOutput(canSeeOutput ? tc.getExpectedOutput() : null)
                            .isSample(tc.getIsSample() == null ? 0 : tc.getIsSample())
                            .build());
        }
        return result;
    }
}
