package com.kece.objective.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.admin.dto.RejudgeResultVO;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageParam;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.objective.dto.AnswerResultVO;
import com.kece.objective.dto.AnswerSubmitRequest;
import com.kece.objective.dto.ObjectiveQuestionRequest;
import com.kece.objective.dto.ObjectiveQuestionVO;
import com.kece.objective.dto.ObjectiveSubmissionListVO;
import com.kece.objective.entity.ObjectiveQuestion;
import com.kece.objective.entity.ObjectiveSubmission;
import com.kece.objective.mapper.ObjectiveQuestionMapper;
import com.kece.objective.mapper.ObjectiveSubmissionMapper;
import com.kece.problemset.service.ProblemSetService;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 客观题业务逻辑。 */
@Service
@RequiredArgsConstructor
public class ObjectiveQuestionService {

    private final ObjectiveQuestionMapper mapper;
    private final ObjectiveSubmissionMapper submissionMapper;
    private final ObjectiveGrader grader;
    private final ObjectMapper objectMapper;
    private final UserMapper userMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProblemSetService problemSetService;

    public PageResult<ObjectiveQuestionVO> list(String type, String keyword, int page, int size) {
        PageParam p = PageParam.of(page, size);
        List<ObjectiveQuestion> items =
                mapper.findPage(type, keyword, 1, null, p.offset(), p.size());
        long total = mapper.count(type, keyword, 1, null);
        java.util.Map<Long, User> authorCache = new java.util.HashMap<>();
        return new PageResult<>(
                items.stream()
                        .map(
                                q -> {
                                    User author = null;
                                    if (q.getCreatedBy() != null) {
                                        author =
                                                authorCache.computeIfAbsent(
                                                        q.getCreatedBy(),
                                                        id -> {
                                                            try {
                                                                return userMapper.findById(id);
                                                            } catch (Exception ignored) {
                                                                return null;
                                                            }
                                                        });
                                    }
                                    return toVO(q, isPrivileged(q), author);
                                })
                        .toList(),
                total,
                p.page(),
                p.size());
    }

    public ObjectiveQuestionVO detail(Long id) {
        ObjectiveQuestion question = getQuestionOrThrow(id);
        if (question.getStatus() != null && question.getStatus() != 1 && !canManage(question)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在");
        }
        return toVO(question, isPrivileged(question));
    }

    public PageResult<ObjectiveQuestionVO> listAdmin(
            String type, String keyword, Integer status, Long createdBy, int page, int size) {
        PageParam p = PageParam.of(page, size);
        AuthUser user = AuthContextHolder.get();
        Long targetCreatedBy =
                (user != null && user.hasRole(Role.ADMIN))
                        ? createdBy
                        : (user == null ? null : user.getId());
        List<ObjectiveQuestion> items =
                mapper.findPage(type, keyword, status, targetCreatedBy, p.offset(), p.size());
        long total = mapper.count(type, keyword, status, targetCreatedBy);
        return new PageResult<>(
                items.stream().map(this::toListVO).toList(), total, p.page(), p.size());
    }

    @Transactional
    public ObjectiveQuestionVO create(ObjectiveQuestionRequest request) {
        ObjectiveQuestion question = new ObjectiveQuestion();
        applyRequest(question, request);
        question.setCreatedBy(AuthContextHolder.currentUserId());
        mapper.insert(question);
        return toVO(question, true);
    }

    @Transactional
    public ObjectiveQuestionVO update(Long id, ObjectiveQuestionRequest request) {
        ObjectiveQuestion question = getQuestionOrThrow(id);
        checkOwnerOrAdmin(question);
        applyRequest(question, request);
        mapper.update(question);
        return toVO(question, true);
    }

    /** 删除客观题：无作答记录时出题人/管理员可删；有作答记录时仅 ADMIN 可强制删除（级联清理作答记录）。 */
    @Transactional
    public void delete(Long id) {
        ObjectiveQuestion question = getQuestionOrThrow(id);
        checkOwnerOrAdmin(question);
        AuthUser user = AuthContextHolder.get();
        boolean admin = user != null && user.hasRole(Role.ADMIN);
        if (submissionMapper.existsByQuestionId(id) && !admin) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该题目已有提交记录，仅管理员可强制删除");
        }
        if (admin) {
            submissionMapper.deleteByQuestionId(id);
        }
        mapper.deleteById(id);
    }

    /** 单条客观题作答重判：仅管理员或该题出题人；用题目当前答案与分值重跑判分，同时覆盖 correct 与 score （多选漏选是半分，只改一个会错）。 */
    @Transactional
    public RejudgeResultVO rejudge(Long submissionId) {
        ObjectiveSubmission submission = submissionMapper.findById(submissionId);
        if (submission == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "作答记录不存在");
        }
        ObjectiveQuestion question = getQuestionOrThrow(submission.getQuestionId());
        checkOwnerOrAdmin(question);
        AnswerResultVO result = grader.grade(question, submission.getAnswer());
        submissionMapper.updateCorrectAndScore(
                submissionId, result.isCorrect() ? 1 : 0, result.getScore());
        return RejudgeResultVO.builder()
                .submissionId(submissionId)
                .status(result.isCorrect() ? "ACCEPTED" : "WRONG_ANSWER")
                .build();
    }

    /**
     * 按题批量重判：重跑该题全部作答记录（依赖 V5 新增的 idx_question_id）。题目改答案/分值后列表的 correctAnswer/fullScore 是活值，存量
     * correct/score 是旧快照，此操作让两者重新一致。
     */
    @Transactional
    public RejudgeResultVO rejudgeByQuestion(Long questionId) {
        ObjectiveQuestion question = getQuestionOrThrow(questionId);
        checkOwnerOrAdmin(question);
        List<ObjectiveSubmission> rows = submissionMapper.findByQuestionId(questionId);
        for (ObjectiveSubmission row : rows) {
            AnswerResultVO result = grader.grade(question, row.getAnswer());
            submissionMapper.updateCorrectAndScore(
                    row.getId(), result.isCorrect() ? 1 : 0, result.getScore());
        }
        return RejudgeResultVO.builder()
                .questionId(questionId)
                .rejudged(rows.size())
                .status("DONE")
                .build();
    }

    /** 作答并即时判分，同时留痕一条作答记录（可重复作答，逐次记录）。 */
    @Transactional
    public AnswerResultVO submit(Long id, AnswerSubmitRequest request) {
        ObjectiveQuestion question = getQuestionOrThrow(id);
        AnswerResultVO result = grader.grade(question, request.getAnswer());

        Long userId = AuthContextHolder.currentUserId();
        if (userId != null) {
            ObjectiveSubmission submission = new ObjectiveSubmission();
            submission.setUserId(userId);
            submission.setQuestionId(id);
            submission.setAnswer(request.getAnswer());
            submission.setCorrect(result.isCorrect() ? 1 : 0);
            submission.setScore(result.getScore());
            submissionMapper.insert(submission);
            if (result.isCorrect() && problemSetService != null) {
                problemSetService.onQuestionSolved(userId, "OBJECTIVE", id);
            }
        }
        return result;
    }

    /** 当前用户已答对的客观题 id（去重），供列表标记「已通过」。 */
    public List<Long> solvedQuestionIds() {
        Long userId = AuthContextHolder.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return submissionMapper.findCorrectQuestionIds(userId);
    }

    /** 客观题作答记录：默认本人记录，可指定 targetUserId 查看该用户作答（非本人自动脱敏作答原文），按 id 倒序分页；correct 为 null 表示不筛对错。 */
    public PageResult<ObjectiveSubmissionListVO> listSubmissions(
            Long targetUserId, Integer correct, int page, int size) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = targetUserId != null ? targetUserId : user.getId();
        PageParam p = PageParam.of(page, size);
        List<ObjectiveSubmissionListVO> list =
                submissionMapper.findPage(userId, null, correct, p.offset(), p.size());
        // 安全防抄袭：前台客观题作答列表仅答题者本人可见作答原文，其他人一律脱敏置空
        if (!userId.equals(user.getId())) {
            for (ObjectiveSubmissionListVO item : list) {
                item.setAnswer(null);
            }
        }
        long total = submissionMapper.count(userId, null, correct);
        return new PageResult<>(list, total, p.page(), p.size());
    }

    public PageResult<ObjectiveSubmissionListVO> listSubmissions(
            Integer correct, int page, int size) {
        return listSubmissions(null, correct, page, size);
    }

    /** 后台客观题作答列表：ADMIN 可跨用户/跨题查询；其余角色一律锁死为本人记录（与代码/数据库题按题目归属的约束方式不同）。 */
    public PageResult<ObjectiveSubmissionListVO> listSubmissionsAdmin(
            Integer correct, Long userId, Long questionId, int page, int size) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long targetUserId = user.hasRole(Role.ADMIN) ? userId : user.getId();
        PageParam p = PageParam.of(page, size);
        List<ObjectiveSubmissionListVO> list =
                submissionMapper.findPage(targetUserId, questionId, correct, p.offset(), p.size());
        long total = submissionMapper.count(targetUserId, questionId, correct);
        return new PageResult<>(list, total, p.page(), p.size());
    }

    private ObjectiveQuestion getQuestionOrThrow(Long id) {
        ObjectiveQuestion question = mapper.findById(id);
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在");
        }
        return question;
    }

    private void checkOwnerOrAdmin(ObjectiveQuestion question) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (user.hasRole(Role.ADMIN)
                || java.util.Objects.equals(question.getCreatedBy(), user.getId())) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    /** 答案可见性：管理员或出题人本人。 */
    private boolean isPrivileged(ObjectiveQuestion question) {
        return isPrivileged(question, AuthContextHolder.get());
    }

    private boolean isPrivileged(ObjectiveQuestion question, AuthUser user) {
        if (user == null) {
            return false;
        }
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        return java.util.Objects.equals(question.getCreatedBy(), user.getId());
    }

    private boolean canManage(ObjectiveQuestion question) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            return false;
        }
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        return java.util.Objects.equals(question.getCreatedBy(), user.getId());
    }

    private ObjectiveQuestionVO toListVO(ObjectiveQuestion question) {
        return ObjectiveQuestionVO.builder()
                .id(question.getId())
                .type(question.getType())
                .content(question.getContent())
                .score(question.getScore())
                .status(question.getStatus())
                .createdBy(question.getCreatedBy())
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private void applyRequest(ObjectiveQuestion question, ObjectiveQuestionRequest request) {
        String type = request.getType() != null ? request.getType().trim().toUpperCase() : "";
        question.setType(type);
        question.setContent(request.getContent());
        if ("JUDGE".equals(type)) {
            // 判断题固定只有对和错两个选项
            question.setOptions(toOptionsJson(List.of("对", "错")));
            String rawAns = request.getAnswer() != null ? request.getAnswer().trim() : "";
            if (rawAns.equals("对")
                    || rawAns.equals("正确")
                    || rawAns.equalsIgnoreCase("TRUE")
                    || rawAns.equalsIgnoreCase("T")
                    || rawAns.equalsIgnoreCase("A")
                    || rawAns.equals("1")) {
                question.setAnswer("对");
            } else if (rawAns.equals("错")
                    || rawAns.equals("错误")
                    || rawAns.equalsIgnoreCase("FALSE")
                    || rawAns.equalsIgnoreCase("F")
                    || rawAns.equalsIgnoreCase("B")
                    || rawAns.equals("0")) {
                question.setAnswer("错");
            } else {
                throw new BusinessException(ResultCode.PARAM_ERROR, "判断题答案只能是对或错");
            }
        } else {
            question.setOptions(toOptionsJson(request.getOptions()));
            question.setAnswer(request.getAnswer());
        }
        question.setMatchType(request.getMatchType() == null ? "EXACT" : request.getMatchType());
        question.setScore(request.getScore() == null ? 5 : request.getScore());
        question.setStatus(request.getStatus() == null ? 1 : request.getStatus());
    }

    private ObjectiveQuestionVO toVO(ObjectiveQuestion question, boolean privileged, User author) {
        return ObjectiveQuestionVO.builder()
                .id(question.getId())
                .type(question.getType())
                .content(question.getContent())
                .options(parseOptions(question.getOptions()))
                .answer(privileged ? question.getAnswer() : null)
                .matchType(question.getMatchType())
                .score(question.getScore())
                .createdBy(question.getCreatedBy())
                .authorName(author != null ? author.getUsername() : null)
                .authorAvatarUrl(author != null ? author.getAvatarUrl() : null)
                .status(question.getStatus())
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private ObjectiveQuestionVO toVO(ObjectiveQuestion question, boolean privileged) {
        User author = null;
        if (question.getCreatedBy() != null) {
            try {
                author = userMapper.findById(question.getCreatedBy());
            } catch (Exception ignored) {
            }
        }
        return toVO(question, privileged, author);
    }

    private String toOptionsJson(List<String> options) {
        try {
            return options == null ? null : objectMapper.writeValueAsString(options);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "选项格式不正确");
        }
    }

    private List<String> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
