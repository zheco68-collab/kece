package com.kece.solution.service;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageParam;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.solution.dto.CreateSolutionRequest;
import com.kece.solution.dto.SolutionDetailVO;
import com.kece.solution.dto.SolutionListItemVO;
import com.kece.solution.dto.SolutionQuestionVO;
import com.kece.solution.dto.UpdateSolutionRequest;
import com.kece.solution.dto.UpdateSolutionStatusRequest;
import com.kece.solution.entity.Solution;
import com.kece.solution.mapper.SolutionMapper;
import com.kece.solution.mapper.SolutionQuestionMapper;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 题解发布、可见性与所有权的唯一业务入口。 */
@Service
@RequiredArgsConstructor
public class SolutionService {

    private static final String TYPE_ALGO = "ALGO";
    private static final String TYPE_DB = "DB";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_HIDDEN = "HIDDEN";
    private static final int MAX_TITLE_CODE_POINTS = 80;
    private static final int MAX_CONTENT_BYTES = 64 * 1024;

    private final SolutionMapper solutionMapper;
    private final SolutionQuestionMapper solutionQuestionMapper;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public PageResult<SolutionListItemVO> listPublic(
            String questionType, Long questionId, int page, int size) {
        String type = normalizeType(questionType);
        validateQuestionId(questionId);
        PageParam safePage = safePage(page, size);
        List<Solution> list =
                solutionMapper.findPublicPage(type, questionId, safePage.offset(), safePage.size());
        return new PageResult<>(
                toListItems(list),
                solutionMapper.countPublic(type, questionId),
                safePage.page(),
                safePage.size());
    }

    @Transactional(readOnly = true)
    public SolutionDetailVO getDetail(Long id) {
        Solution solution = requireSolution(id);
        AuthUser viewer = AuthContextHolder.get();
        boolean owner = viewer != null && solution.getAuthorId().equals(viewer.getId());
        boolean admin = isAdmin(viewer);
        SolutionQuestionVO question =
                findQuestion(solution.getQuestionType(), solution.getQuestionId(), false);
        boolean questionPublic = isQuestionPublic(question);
        boolean publicReadable =
                solution.getDeletedAt() == null
                        && STATUS_PUBLISHED.equals(solution.getStatus())
                        && questionPublic;
        if (!publicReadable && !owner && !admin) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        if (solution.getDeletedAt() != null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        SolutionDetailVO detail =
                toDetail(solution, question, userMapper.findById(solution.getAuthorId()));
        if (!owner && !admin) {
            detail.setLastManagementReason(null);
        }
        return detail;
    }

    @Transactional
    public SolutionDetailVO create(CreateSolutionRequest request) {
        Long authorId = requireCurrentUserId();
        String questionType = normalizeType(request.getQuestionType());
        validateQuestionId(request.getQuestionId());
        String title = normalizeTitle(request.getTitle());
        String contentMd = validateContent(request.getContentMd());
        String requestId = request.getClientRequestId().trim();
        String requestHash =
                createRequestHash(questionType, request.getQuestionId(), title, contentMd);

        Solution existing = solutionMapper.findByAuthorAndRequestId(authorId, requestId);
        if (existing != null) {
            return resolveIdempotentCreate(existing, requestHash);
        }

        SolutionQuestionVO question = findQuestion(questionType, request.getQuestionId(), true);
        if (!isQuestionPublic(question)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "关联题目不存在或当前不可公开");
        }

        Solution solution = new Solution();
        solution.setQuestionType(questionType);
        solution.setQuestionId(request.getQuestionId());
        solution.setAuthorId(authorId);
        solution.setTitle(title);
        solution.setContentMd(contentMd);
        solution.setStatus(STATUS_PUBLISHED);
        solution.setVersion(1L);
        solution.setClientRequestId(requestId);
        solution.setRequestHash(requestHash);
        try {
            solutionMapper.insert(solution);
        } catch (DuplicateKeyException ex) {
            Solution raced = solutionMapper.findByAuthorAndRequestId(authorId, requestId);
            if (raced == null) {
                throw ex;
            }
            return resolveIdempotentCreate(raced, requestHash);
        }
        Solution created = requireActiveSolution(solution.getId());
        return toDetail(created, question, userMapper.findById(authorId));
    }

    @Transactional
    public SolutionDetailVO update(Long id, UpdateSolutionRequest request) {
        Long authorId = requireCurrentUserId();
        Solution existing = requireActiveSolution(id);
        if (!existing.getAuthorId().equals(authorId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能编辑自己的题解");
        }
        validateVersion(request.getVersion());
        String title = normalizeTitle(request.getTitle());
        String contentMd = validateContent(request.getContentMd());
        int changed =
                solutionMapper.updateContentIfVersion(
                        id, authorId, title, contentMd, request.getVersion());
        if (changed != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "内容已在其他位置更新，请读取最新版本后重试");
        }
        Solution updated = requireActiveSolution(id);
        return toDetail(
                updated,
                findQuestion(updated.getQuestionType(), updated.getQuestionId(), false),
                userMapper.findById(authorId));
    }

    @Transactional
    public void delete(Long id, Long version) {
        Long userId = requireCurrentUserId();
        validateVersion(version);
        Solution solution = requireActiveSolution(id);
        if (!solution.getAuthorId().equals(userId) && !isAdmin(AuthContextHolder.get())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能删除自己的题解");
        }
        if (solutionMapper.softDeleteIfVersion(id, version) != 1) {
            throw new BusinessException(ResultCode.CONFLICT);
        }
    }

    @Transactional(readOnly = true)
    public PageResult<SolutionListItemVO> listMine(int page, int size) {
        Long authorId = requireCurrentUserId();
        PageParam safePage = safePage(page, size);
        List<Solution> list =
                solutionMapper.findMinePage(authorId, safePage.offset(), safePage.size());
        return new PageResult<>(
                toListItems(list),
                solutionMapper.countMine(authorId),
                safePage.page(),
                safePage.size());
    }

    @Transactional(readOnly = true)
    public PageResult<SolutionListItemVO> listAdmin(
            String keyword,
            Long questionId,
            String questionType,
            String status,
            int page,
            int size) {
        PageParam safePage = safePage(page, size);
        String type =
                questionType == null || questionType.isBlank() ? null : normalizeType(questionType);
        String normalizedStatus =
                status == null || status.isBlank() ? null : normalizeStatus(status);
        if (questionId != null) {
            validateQuestionId(questionId);
        }
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        List<Solution> list =
                solutionMapper.findAdminPage(
                        normalizedKeyword,
                        questionId,
                        type,
                        normalizedStatus,
                        safePage.offset(),
                        safePage.size());
        return new PageResult<>(
                toListItems(list),
                solutionMapper.countAdmin(normalizedKeyword, questionId, type, normalizedStatus),
                safePage.page(),
                safePage.size());
    }

    @Transactional(readOnly = true)
    public SolutionDetailVO getAdminDetail(Long id) {
        Solution solution = requireActiveSolution(id);
        return toDetail(
                solution,
                findQuestion(solution.getQuestionType(), solution.getQuestionId(), false),
                userMapper.findById(solution.getAuthorId()));
    }

    @Transactional
    public SolutionDetailVO updateStatus(Long id, UpdateSolutionStatusRequest request) {
        validateVersion(request.getVersion());
        String status = normalizeStatus(request.getStatus());
        Solution solution = requireActiveSolution(id);
        if (STATUS_PUBLISHED.equals(status)) {
            SolutionQuestionVO question =
                    findQuestion(solution.getQuestionType(), solution.getQuestionId(), true);
            if (!isQuestionPublic(question)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "关联题目不存在或当前不可公开");
            }
        }
        String reason = STATUS_HIDDEN.equals(status) ? normalizeReason(request.getReason()) : null;
        if (solutionMapper.updateStatusIfVersion(id, status, reason, request.getVersion()) != 1) {
            throw new BusinessException(ResultCode.CONFLICT);
        }
        Solution updated = requireActiveSolution(id);
        return toDetail(
                updated,
                findQuestion(updated.getQuestionType(), updated.getQuestionId(), false),
                userMapper.findById(updated.getAuthorId()));
    }

    private SolutionDetailVO resolveIdempotentCreate(Solution existing, String requestHash) {
        if (!requestHash.equals(existing.getRequestHash())) {
            throw new BusinessException(ResultCode.CONFLICT, "同一请求标识不能用于不同题解");
        }
        if (existing.getDeletedAt() != null) {
            throw new BusinessException(ResultCode.CONFLICT, "原发布请求对应的题解已删除");
        }
        return toDetail(
                existing,
                findQuestion(existing.getQuestionType(), existing.getQuestionId(), false),
                userMapper.findById(existing.getAuthorId()));
    }

    private List<SolutionListItemVO> toListItems(List<Solution> solutions) {
        if (solutions.isEmpty()) {
            return List.of();
        }
        Map<Long, User> authors =
                userMapper
                        .findByIds(
                                solutions.stream()
                                        .map(Solution::getAuthorId)
                                        .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, SolutionQuestionVO> questions = loadQuestions(solutions);
        return solutions.stream()
                .map(
                        solution ->
                                toListItem(
                                        solution,
                                        questions.get(questionKey(solution)),
                                        authors.get(solution.getAuthorId())))
                .toList();
    }

    private Map<String, SolutionQuestionVO> loadQuestions(Collection<Solution> solutions) {
        Set<Long> algoIds = new HashSet<>();
        Set<Long> dbIds = new HashSet<>();
        for (Solution solution : solutions) {
            if (TYPE_ALGO.equals(solution.getQuestionType())) {
                algoIds.add(solution.getQuestionId());
            } else if (TYPE_DB.equals(solution.getQuestionType())) {
                dbIds.add(solution.getQuestionId());
            }
        }
        Map<String, SolutionQuestionVO> result = new HashMap<>();
        if (!algoIds.isEmpty()) {
            solutionQuestionMapper
                    .findAlgoByIds(algoIds)
                    .forEach(question -> result.put(TYPE_ALGO + ':' + question.getId(), question));
        }
        if (!dbIds.isEmpty()) {
            solutionQuestionMapper
                    .findDbByIds(dbIds)
                    .forEach(question -> result.put(TYPE_DB + ':' + question.getId(), question));
        }
        return result;
    }

    private SolutionListItemVO toListItem(
            Solution solution, SolutionQuestionVO question, User author) {
        SolutionListItemVO result = new SolutionListItemVO();
        result.setId(solution.getId());
        result.setQuestionType(solution.getQuestionType());
        result.setQuestionId(solution.getQuestionId());
        result.setQuestionPublic(isQuestionPublic(question));
        result.setQuestionTitle(question == null ? "题目当前不可用" : question.getTitle());
        result.setAuthorId(solution.getAuthorId());
        result.setAuthorName(author == null ? "未知用户" : author.getUsername());
        result.setTitle(solution.getTitle());
        result.setStatus(solution.getStatus());
        result.setVersion(solution.getVersion());
        result.setCreatedAt(solution.getCreatedAt());
        result.setUpdatedAt(solution.getUpdatedAt());
        return result;
    }

    private SolutionDetailVO toDetail(Solution solution, SolutionQuestionVO question, User author) {
        SolutionDetailVO result = new SolutionDetailVO();
        result.setId(solution.getId());
        result.setQuestionType(solution.getQuestionType());
        result.setQuestionId(solution.getQuestionId());
        result.setQuestionPublic(isQuestionPublic(question));
        result.setQuestionTitle(
                question == null || !isQuestionPublic(question) ? "题目当前不可用" : question.getTitle());
        result.setAuthorId(solution.getAuthorId());
        result.setAuthorName(author == null ? "未知用户" : author.getUsername());
        result.setTitle(solution.getTitle());
        result.setContentMd(solution.getContentMd());
        result.setStatus(solution.getStatus());
        result.setVersion(solution.getVersion());
        result.setCreatedAt(solution.getCreatedAt());
        result.setUpdatedAt(solution.getUpdatedAt());
        result.setLastManagementReason(solution.getLastManagementReason());
        return result;
    }

    private Solution requireSolution(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "题解 ID 不合法");
        }
        Solution solution = solutionMapper.findById(id);
        if (solution == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return solution;
    }

    private Solution requireActiveSolution(Long id) {
        Solution solution = requireSolution(id);
        if (solution.getDeletedAt() != null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return solution;
    }

    private SolutionQuestionVO findQuestion(String type, Long questionId, boolean forUpdate) {
        return switch (type) {
            case TYPE_ALGO ->
                    forUpdate
                            ? solutionQuestionMapper.findAlgoForUpdate(questionId)
                            : solutionQuestionMapper.findAlgo(questionId);
            case TYPE_DB ->
                    forUpdate
                            ? solutionQuestionMapper.findDbForUpdate(questionId)
                            : solutionQuestionMapper.findDb(questionId);
            default -> throw new BusinessException(ResultCode.PARAM_ERROR, "题型不支持题解");
        };
    }

    private PageParam safePage(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        long maxPage = (Integer.MAX_VALUE / (long) safeSize) + 1;
        if (page > maxPage) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "页码过大");
        }
        return PageParam.of(page, safeSize);
    }

    private Long requireCurrentUserId() {
        Long userId = AuthContextHolder.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private boolean isAdmin(AuthUser user) {
        return user != null && user.hasRole(Role.ADMIN);
    }

    private boolean isQuestionPublic(SolutionQuestionVO question) {
        return question != null && Integer.valueOf(1).equals(question.getStatus());
    }

    private String normalizeType(String type) {
        if (type == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "题型不能为空");
        }
        String result = type.trim().toUpperCase();
        if (!TYPE_ALGO.equals(result) && !TYPE_DB.equals(result)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "题型仅支持 ALGO 或 DB");
        }
        return result;
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态不能为空");
        }
        String result = status.trim().toUpperCase();
        if (!STATUS_PUBLISHED.equals(result) && !STATUS_HIDDEN.equals(result)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态仅支持 PUBLISHED 或 HIDDEN");
        }
        return result;
    }

    private void validateQuestionId(Long questionId) {
        if (questionId == null || questionId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "题目 ID 不合法");
        }
    }

    private void validateVersion(Long version) {
        if (version == null || version <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "版本不合法");
        }
    }

    private String normalizeTitle(String title) {
        String result = title == null ? "" : title.trim();
        if (result.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "标题不能为空");
        }
        if (result.codePointCount(0, result.length()) > MAX_TITLE_CODE_POINTS) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "标题不能超过 80 个字符");
        }
        return result;
    }

    private String validateContent(String contentMd) {
        if (contentMd == null || contentMd.trim().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "正文不能为空");
        }
        if (contentMd.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "正文不能超过 64 KiB");
        }
        return contentMd;
    }

    private String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String result = reason.trim();
        if (result.codePointCount(0, result.length()) > 200) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "原因不能超过 200 个字符");
        }
        return result.isEmpty() ? null : result;
    }

    private String createRequestHash(String type, Long questionId, String title, String contentMd) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = type + '\n' + questionId + '\n' + title + '\n' + contentMd;
            return HexFormat.of()
                    .formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String questionKey(Solution solution) {
        return solution.getQuestionType() + ':' + solution.getQuestionId();
    }
}
