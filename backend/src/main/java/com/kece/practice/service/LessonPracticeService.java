package com.kece.practice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.database.entity.DbQuestion;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.objective.dto.AnswerResultVO;
import com.kece.objective.entity.ObjectiveQuestion;
import com.kece.objective.mapper.ObjectiveQuestionMapper;
import com.kece.objective.service.ObjectiveGrader;
import com.kece.practice.dto.*;
import com.kece.practice.entity.*;
import com.kece.practice.mapper.*;
import com.kece.question.entity.Question;
import com.kece.question.mapper.QuestionMapper;
import com.kece.question.strategy.QuestionMeta;
import com.kece.question.strategy.QuestionStrategyFactory;
import com.kece.question.strategy.QuestionTypeStrategy;
import com.kece.submission.entity.Submission;
import com.kece.submission.mapper.SubmissionMapper;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LessonPracticeService {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PRACTICE_SUB_CTX_PREFIX = "practice:sub_ctx:";

    private final LessonPracticeMapper practiceMapper;
    private final LessonPracticeQuestionMapper questionMapper;
    private final LessonPracticeMemberMapper memberMapper;
    private final LessonPracticeSubmissionMapper submissionMapper;
    private final ObjectiveQuestionMapper objectiveQuestionMapper;
    private final QuestionMapper questionBaseMapper;
    private final DbQuestionMapper dbQuestionMapper;
    private final SubmissionMapper submissionRecordMapper;
    private final DbSubmissionMapper dbSubmissionMapper;
    private final UserMapper userMapper;
    private final ObjectiveGrader objectiveGrader;
    private final ObjectMapper objectMapper;
    private final QuestionStrategyFactory strategyFactory;
    private final StringRedisTemplate redis;

    /** 定时将已到期的 ACTIVE 随堂课练收口为 ENDED（保留原计划 end_time 不变）。 */
    @Scheduled(fixedDelay = 30000)
    public void closeExpiredPracticesJob() {
        try {
            int closed = practiceMapper.closeExpiredPractices();
            if (closed > 0) {
                log.info("自动归档已到期随堂课练 {} 场", closed);
            }
        } catch (Exception e) {
            log.debug("自动归档到期随堂课练扫描异常: {}", e.getMessage());
        }
    }

    /** 实时校准单场课练是否已到期（保留原计划 end_time 不篡改）。 */
    private boolean syncPracticeStatusIfExpired(LessonPractice practice) {
        if (practice == null) {
            return false;
        }
        if ("ACTIVE".equals(practice.getStatus())
                && practice.getEndTime() != null
                && !practice.getEndTime().isAfter(LocalDateTime.now())) {
            practice.setStatus("ENDED");
            practiceMapper.updateStatus(practice.getId(), "ENDED");
            return true;
        }
        return "ENDED".equals(practice.getStatus());
    }

    /** 判断指定用户是否为该课练的授课教师本人或系统管理员。 */
    private boolean canManagePractice(LessonPractice practice, Long userId) {
        if (practice == null || userId == null) {
            return false;
        }
        if (Objects.equals(practice.getTeacherId(), userId)) {
            return true;
        }
        AuthUser authUser = AuthContextHolder.get();
        if (authUser != null && Objects.equals(authUser.getId(), userId)) {
            return authUser.hasRole(Role.ADMIN);
        }
        User user = userMapper.findById(userId);
        return user != null && user.getRoles() != null && user.getRoles().contains("ADMIN");
    }

    /** 生成唯一 6 位大写房间口令。 */
    private String generateAccessCode() {
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder(6);
            for (int j = 0; j < 6; j++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (practiceMapper.findByAccessCode(code) == null) {
                return code;
            }
        }
        return "KE" + (1000 + RANDOM.nextInt(9000));
    }

    /** 教师发起新课练。 */
    @Transactional
    public PracticeSummaryVO createPractice(Long teacherId, CreatePracticeRequest req) {
        String accessCode = generateAccessCode();
        LocalDateTime now = LocalDateTime.now();
        int duration = req.getDurationMinutes() != null ? Math.max(0, req.getDurationMinutes()) : 0;
        LocalDateTime endTime = duration > 0 ? now.plusMinutes(duration) : null;

        LessonPractice practice =
                LessonPractice.builder()
                        .title(req.getTitle().trim())
                        .description(req.getDescription())
                        .accessCode(accessCode)
                        .durationMinutes(duration)
                        .status("ACTIVE")
                        .teacherId(teacherId)
                        .startTime(now)
                        .endTime(endTime)
                        .build();

        practiceMapper.insert(practice);

        if (req.getQuestions() != null && !req.getQuestions().isEmpty()) {
            boolean isAdmin = isAdminUser(teacherId);
            List<LessonPracticeQuestion> qList = new ArrayList<>();
            Set<String> seenKeys = new HashSet<>();
            int sort = 1;
            for (PracticeQuestionItem item : req.getQuestions()) {
                String type =
                        item.getQuestionType() != null
                                ? item.getQuestionType().trim().toUpperCase()
                                : "";
                String dedupKey = type + ":" + item.getQuestionId();
                if (!seenKeys.add(dedupKey)) {
                    continue;
                }
                String title = item.getTitle();
                QuestionTypeStrategy strategy = strategyFactory.getStrategy(type);
                QuestionMeta meta = strategy.resolveQuestionMeta(item.getQuestionId());
                if (!isAdmin
                        && (meta.getCreatedBy() == null
                                || !meta.getCreatedBy().equals(teacherId))) {
                    throw new BusinessException(
                            ResultCode.PARAM_ERROR,
                            "教师只能选择自己出的" + strategy.getDisplayName() + "（" + meta.getTitle() + "）");
                }
                if (title == null || title.isBlank()) {
                    title = meta.getTitle();
                }

                qList.add(
                        LessonPracticeQuestion.builder()
                                .practiceId(practice.getId())
                                .questionId(item.getQuestionId())
                                .questionType(type)
                                .title(title)
                                .score(item.getScore() != null ? item.getScore() : 10)
                                .sortOrder(
                                        item.getSortOrder() != null ? item.getSortOrder() : sort++)
                                .build());
            }
            if (!qList.isEmpty()) {
                questionMapper.insertBatch(qList);
            }
        }

        return getSummaryVO(practice, teacherId);
    }

    /** 教师/管理员二次编辑课练基础配置与题集（无损 Diff 同步，保留已有作答与主键）。 */
    @Transactional
    public PracticeSummaryVO updatePractice(
            Long practiceId, Long teacherId, UpdatePracticeRequest req) {
        LessonPractice practice = practiceMapper.findById(practiceId);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        checkPracticeManagePermission(practice, teacherId);

        int duration =
                req.getDurationMinutes() != null
                        ? Math.max(0, req.getDurationMinutes())
                        : (practice.getDurationMinutes() != null
                                ? practice.getDurationMinutes()
                                : 0);
        LocalDateTime baseStart =
                practice.getStartTime() != null ? practice.getStartTime() : LocalDateTime.now();
        LocalDateTime newEndTime = duration > 0 ? baseStart.plusMinutes(duration) : null;
        String newStatus =
                (newEndTime == null || newEndTime.isAfter(LocalDateTime.now()))
                        ? "ACTIVE"
                        : "ENDED";

        practiceMapper.updatePracticeConfig(
                practiceId,
                req.getTitle().trim(),
                req.getDescription(),
                duration,
                newEndTime,
                newStatus);

        if (req.getQuestions() != null && !req.getQuestions().isEmpty()) {
            boolean isAdmin = isAdminUser(teacherId);
            List<LessonPracticeQuestion> existingList = questionMapper.listByPracticeId(practiceId);
            Map<String, LessonPracticeQuestion> existingByKey = new HashMap<>();
            for (LessonPracticeQuestion eq : existingList) {
                String k = eq.getQuestionType().trim().toUpperCase() + ":" + eq.getQuestionId();
                existingByKey.putIfAbsent(k, eq);
            }

            Set<Long> retainedIds = new HashSet<>();
            Set<String> seenKeys = new HashSet<>();
            boolean needRecalculateMembers = false;
            int sort = 1;

            for (PracticeQuestionItem item : req.getQuestions()) {
                String type =
                        item.getQuestionType() != null
                                ? item.getQuestionType().trim().toUpperCase()
                                : "";
                String key = type + ":" + item.getQuestionId();
                if (!seenKeys.add(key)) {
                    continue;
                }

                QuestionTypeStrategy strategy = strategyFactory.getStrategy(type);
                QuestionMeta meta = strategy.resolveQuestionMeta(item.getQuestionId());
                String title =
                        (item.getTitle() == null || item.getTitle().isBlank())
                                ? meta.getTitle()
                                : item.getTitle();
                int newScore = item.getScore() != null ? item.getScore() : 10;
                int sortOrder = item.getSortOrder() != null ? item.getSortOrder() : sort++;

                LessonPracticeQuestion oldPq = existingByKey.get(key);
                if (oldPq != null) {
                    retainedIds.add(oldPq.getId());
                    int oldScore = oldPq.getScore() != null ? oldPq.getScore() : 10;
                    oldPq.setTitle(title);
                    oldPq.setScore(newScore);
                    oldPq.setSortOrder(sortOrder);
                    questionMapper.updateQuestionItem(oldPq);

                    if (oldScore != newScore) {
                        needRecalculateMembers = true;
                        rescaleQuestionSubmissions(oldPq.getId(), oldScore, newScore);
                    }
                } else {
                    if (!isAdmin
                            && (meta.getCreatedBy() == null
                                    || !meta.getCreatedBy().equals(teacherId))) {
                        throw new BusinessException(
                                ResultCode.PARAM_ERROR,
                                "教师只能选择自己出的"
                                        + strategy.getDisplayName()
                                        + "（"
                                        + meta.getTitle()
                                        + "）");
                    }
                    LessonPracticeQuestion created =
                            LessonPracticeQuestion.builder()
                                    .practiceId(practiceId)
                                    .questionId(item.getQuestionId())
                                    .questionType(type)
                                    .title(title)
                                    .score(newScore)
                                    .sortOrder(sortOrder)
                                    .build();
                    questionMapper.insertOne(created);
                }
            }

            List<Long> removedIds =
                    existingList.stream()
                            .map(LessonPracticeQuestion::getId)
                            .filter(id -> !retainedIds.contains(id))
                            .toList();
            if (!removedIds.isEmpty()) {
                needRecalculateMembers = true;
                submissionMapper.deleteByPracticeQuestionIds(removedIds);
                questionMapper.deleteByIds(removedIds);
            }

            if (needRecalculateMembers) {
                List<LessonPracticeMember> members = memberMapper.listByPracticeId(practiceId);
                for (LessonPracticeMember m : members) {
                    recalculateMemberScore(practiceId, m.getUserId());
                }
            }
        }

        practice = practiceMapper.findById(practiceId);
        return getSummaryVO(practice, teacherId);
    }

    private void rescaleQuestionSubmissions(
            Long practiceQuestionId, int oldFullScore, int newFullScore) {
        List<LessonPracticeSubmission> subs =
                submissionMapper.listByPracticeQuestionId(practiceQuestionId);
        for (LessonPracticeSubmission sub : subs) {
            if ("ACCEPTED".equals(sub.getStatus())) {
                sub.setScore(newFullScore);
                submissionMapper.updateSubmission(sub);
            } else if (sub.getScore() != null && sub.getScore() > 0 && oldFullScore > 0) {
                int rescaled =
                        (int) Math.round((double) sub.getScore() * newFullScore / oldFullScore);
                if (rescaled == 0 && newFullScore > 0) {
                    rescaled = 1;
                }
                sub.setScore(Math.min(rescaled, newFullScore));
                submissionMapper.updateSubmission(sub);
            }
        }
    }

    private boolean isAdminUser(Long userId) {
        AuthUser authUser = AuthContextHolder.get();
        if (authUser != null && Objects.equals(authUser.getId(), userId)) {
            return authUser.hasRole(Role.ADMIN);
        }
        User user = userId != null ? userMapper.findById(userId) : null;
        return user != null && user.getRoles() != null && user.getRoles().contains("ADMIN");
    }

    /** 兼容旧签名的入场方法。 */
    @Transactional
    public PracticeDetailVO joinPractice(Long userId, String accessCode) {
        return joinPractice(userId, accessCode, null);
    }

    /** 学生或教师通过 6 位口令加入课练房间（支持行内指定 expectedPracticeId 防串房，授课教师/管理员免插学生表）。 */
    @Transactional
    public PracticeDetailVO joinPractice(Long userId, String accessCode, Long expectedPracticeId) {
        if (accessCode == null || accessCode.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请输入 6 位课堂口令");
        }
        String code = accessCode.trim().toUpperCase();
        LessonPractice practice = practiceMapper.findByAccessCode(code);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练口令不存在，请核对后重试");
        }
        if (expectedPracticeId != null && !Objects.equals(practice.getId(), expectedPracticeId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "输入的口令与所选课练不匹配，请核对 6 位课堂口令");
        }

        syncPracticeStatusIfExpired(practice);

        // 授课教师本人与系统管理员直接进入预览视角，不插入学生成员表污染班级均分
        if (canManagePractice(practice, userId)) {
            return getPracticeDetail(code, userId);
        }

        LessonPracticeMember member = memberMapper.findByPracticeAndUser(practice.getId(), userId);
        if ("ENDED".equals(practice.getStatus()) && member == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该课练已截止收卷，无法入场");
        }
        if (member == null) {
            member =
                    LessonPracticeMember.builder()
                            .practiceId(practice.getId())
                            .userId(userId)
                            .totalScore(0)
                            .solvedCount(0)
                            .penaltyTime(0L)
                            .joinedAt(LocalDateTime.now())
                            .submitted(0)
                            .build();
            memberMapper.insert(member);
        }

        return getPracticeDetail(code, userId);
    }

    /** 获取课练房间主页（题目列表与作答概览，强制校验口令入场状态或管理权限）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PracticeDetailVO getPracticeDetail(String accessCode, Long userId) {
        String code = accessCode.trim().toUpperCase();
        LessonPractice practice = practiceMapper.findByAccessCode(code);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练口令不存在");
        }

        syncPracticeStatusIfExpired(practice);

        boolean canManage = canManagePractice(practice, userId);
        LessonPracticeMember myMember =
                userId != null
                        ? memberMapper.findByPracticeAndUser(practice.getId(), userId)
                        : null;
        if (!canManage && myMember == null) {
            if ("ENDED".equals(practice.getStatus())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "该课练已结束，未参练人员无法查看");
            }
            throw new BusinessException(ResultCode.FORBIDDEN, "请先输入 6 位课堂口令解锁入场");
        }

        User teacher = userMapper.findById(practice.getTeacherId());
        String teacherName = teacher != null ? teacher.getUsername() : "教师";
        int memberCount = memberMapper.countByPracticeId(practice.getId());

        LocalDateTime now = LocalDateTime.now();
        Long remainingSeconds = null;
        if ("ENDED".equals(practice.getStatus())) {
            remainingSeconds = 0L;
        } else if (practice.getEndTime() != null) {
            remainingSeconds =
                    Math.max(0, Duration.between(now, practice.getEndTime()).getSeconds());
        }

        boolean mySubmitted =
                myMember != null && Integer.valueOf(1).equals(myMember.getSubmitted());
        boolean revealAnswer = "ENDED".equals(practice.getStatus()) || mySubmitted || canManage;

        List<LessonPracticeQuestion> allQuestions =
                questionMapper.listByPracticeId(practice.getId());

        List<LessonPracticeSubmission> mySubmissions =
                userId != null
                        ? submissionMapper.listByPracticeAndUser(practice.getId(), userId)
                        : Collections.emptyList();

        boolean refreshedJudging = false;
        for (LessonPracticeSubmission s : mySubmissions) {
            if (s.getSubmissionId() != null && isJudging(s.getStatus())) {
                onPracticalJudgeCompleted(s.getQuestionType(), s.getSubmissionId());
                refreshedJudging = true;
            }
        }
        if (refreshedJudging && userId != null) {
            mySubmissions = submissionMapper.listByPracticeAndUser(practice.getId(), userId);
            myMember = memberMapper.findByPracticeAndUser(practice.getId(), userId);
        }

        Map<Long, LessonPracticeSubmission> subMap =
                mySubmissions.stream()
                        .filter(s -> s.getPracticeQuestionId() != null)
                        .collect(
                                Collectors.toMap(
                                        LessonPracticeSubmission::getPracticeQuestionId,
                                        s -> s,
                                        (existing, replacement) -> {
                                            int eScore =
                                                    existing.getScore() != null
                                                            ? existing.getScore()
                                                            : 0;
                                            int rScore =
                                                    replacement.getScore() != null
                                                            ? replacement.getScore()
                                                            : 0;
                                            return eScore >= rScore ? existing : replacement;
                                        }));

        List<PracticeObjectiveQuestionVO> objectiveList = new ArrayList<>();
        List<PracticePracticalQuestionVO> practicalList = new ArrayList<>();

        for (LessonPracticeQuestion pq : allQuestions) {
            LessonPracticeSubmission sub = subMap.get(pq.getId());
            if ("OBJECTIVE".equals(pq.getQuestionType())) {
                ObjectiveQuestion oq = objectiveQuestionMapper.findById(pq.getQuestionId());
                List<String> options = parseOptions(oq != null ? oq.getOptions() : null);
                boolean answered =
                        sub != null && sub.getAnswer() != null && !sub.getAnswer().isBlank();
                Boolean isCorrect =
                        answered ? Boolean.valueOf("ACCEPTED".equals(sub.getStatus())) : null;

                objectiveList.add(
                        PracticeObjectiveQuestionVO.builder()
                                .practiceQuestionId(pq.getId())
                                .questionId(pq.getQuestionId())
                                .type(oq != null ? oq.getType() : "SINGLE")
                                .content(oq != null ? oq.getContent() : pq.getTitle())
                                .options(options)
                                .score(pq.getScore())
                                .sortOrder(pq.getSortOrder())
                                .userAnswer(answered ? sub.getAnswer() : null)
                                .userScore(answered ? sub.getScore() : null)
                                .isCorrect(isCorrect)
                                .answered(answered)
                                .correctAnswer(revealAnswer && oq != null ? oq.getAnswer() : null)
                                .build());
            } else {
                QuestionTypeStrategy strategy = strategyFactory.getStrategy(pq.getQuestionType());
                if (strategy.isPractical()) {
                    Long displaySubmissionId =
                            sub != null
                                    ? (sub.getBestSubmissionId() != null
                                            ? sub.getBestSubmissionId()
                                            : sub.getSubmissionId())
                                    : null;
                    strategy.buildPracticePracticalVO(
                                    pq.getQuestionId(),
                                    pq.getId(),
                                    pq.getScore(),
                                    pq.getSortOrder(),
                                    sub != null ? sub.getStatus() : "UNATTEMPTED",
                                    sub != null && sub.getScore() != null ? sub.getScore() : 0,
                                    displaySubmissionId)
                            .ifPresent(practicalList::add);
                }
            }
        }

        return PracticeDetailVO.builder()
                .id(practice.getId())
                .title(practice.getTitle())
                .description(practice.getDescription())
                .accessCode(practice.getAccessCode())
                .durationMinutes(practice.getDurationMinutes())
                .status(practice.getStatus())
                .teacherId(practice.getTeacherId())
                .teacherName(teacherName)
                .memberCount(memberCount)
                .startTime(practice.getStartTime())
                .endTime(practice.getEndTime())
                .remainingSeconds(remainingSeconds)
                .canManage(canManage)
                .myJoined(myMember != null)
                .myScore(
                        myMember != null && myMember.getTotalScore() != null
                                ? myMember.getTotalScore()
                                : 0)
                .mySolvedCount(
                        myMember != null && myMember.getSolvedCount() != null
                                ? myMember.getSolvedCount()
                                : 0)
                .mySubmitted(mySubmitted)
                .objectiveQuestions(objectiveList)
                .practicalQuestions(practicalList)
                .build();
    }

    /** 客观题增量保存与即时判分（方案 B：未交卷时即时返回本题 AC/PARTIAL/WA 与得分，严格隐藏标准答案文本）。 */
    @Transactional
    public PracticeObjectiveResultVO submitObjective(
            String accessCode, Long userId, PracticeObjectiveSubmitRequest req) {
        String code = accessCode.trim().toUpperCase();
        LessonPractice practice = practiceMapper.findByAccessCode(code);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        if (syncPracticeStatusIfExpired(practice)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "本次课练已截止收卷，无法继续作答");
        }

        LessonPracticeMember member = memberMapper.findByPracticeAndUser(practice.getId(), userId);
        if (member == null) {
            if (canManagePractice(practice, userId)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "当前为教师管理预览视角，不记录学生作答成绩");
            }
            throw new BusinessException(ResultCode.FORBIDDEN, "请先输入 6 位课堂口令加入课练后再作答");
        }
        if (Integer.valueOf(1).equals(member.getSubmitted())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "您已正式交卷，无法继续修改答案");
        }

        List<LessonPracticeQuestion> objQuestions =
                questionMapper.listByPracticeId(practice.getId()).stream()
                        .filter(q -> "OBJECTIVE".equals(q.getQuestionType()))
                        .toList();
        Map<Long, LessonPracticeQuestion> objMap =
                objQuestions.stream()
                        .collect(Collectors.toMap(LessonPracticeQuestion::getId, q -> q));

        LocalDateTime now = LocalDateTime.now();
        List<PracticeObjectiveResultVO.ItemResult> itemResults = new ArrayList<>();

        if (req.getAnswers() != null) {
            for (Map.Entry<Long, String> entry : req.getAnswers().entrySet()) {
                Long pqId = entry.getKey();
                LessonPracticeQuestion pq = objMap.get(pqId);
                if (pq == null) {
                    continue;
                }
                String submittedAnswer = entry.getValue() != null ? entry.getValue().trim() : "";
                LessonPracticeSubmission existSub =
                        submissionMapper.findByPracticeUserQuestion(practice.getId(), userId, pqId);

                if (submittedAnswer.isEmpty() && existSub == null) {
                    continue;
                }

                ObjectiveQuestion oq = objectiveQuestionMapper.findById(pq.getQuestionId());
                AnswerResultVO gradeResult =
                        (oq != null && !submittedAnswer.isEmpty())
                                ? objectiveGrader.grade(oq, submittedAnswer)
                                : AnswerResultVO.builder()
                                        .correct(false)
                                        .score(0)
                                        .fullScore(pq.getScore())
                                        .build();

                int questionScore =
                        (int)
                                Math.round(
                                        (double) gradeResult.getScore()
                                                * pq.getScore()
                                                / (gradeResult.getFullScore() > 0
                                                        ? gradeResult.getFullScore()
                                                        : 1));
                if (gradeResult.getScore() > 0 && questionScore == 0 && pq.getScore() > 0) {
                    questionScore = 1;
                }

                String status =
                        gradeResult.isCorrect()
                                ? "ACCEPTED"
                                : (questionScore > 0 ? "PARTIAL" : "WRONG_ANSWER");

                if (existSub != null) {
                    int prevScore = existSub.getScore() != null ? existSub.getScore() : 0;
                    int prevAttempts =
                            existSub.getAttemptCount() != null ? existSub.getAttemptCount() : 1;
                    boolean changed = !Objects.equals(existSub.getAnswer(), submittedAnswer);
                    existSub.setAnswer(submittedAnswer);
                    existSub.setStatus(status);
                    existSub.setScore(questionScore);
                    if (changed) {
                        existSub.setAttemptCount(prevAttempts + 1);
                    }
                    if (questionScore > 0) {
                        if (questionScore > prevScore || existSub.getBestAchievedAt() == null) {
                            existSub.setBestAchievedAt(now);
                        }
                    } else {
                        existSub.setBestAchievedAt(null);
                    }
                    submissionMapper.updateSubmission(existSub);
                } else {
                    LessonPracticeSubmission newSub =
                            LessonPracticeSubmission.builder()
                                    .practiceId(practice.getId())
                                    .practiceQuestionId(pqId)
                                    .userId(userId)
                                    .questionType("OBJECTIVE")
                                    .answer(submittedAnswer)
                                    .status(status)
                                    .score(questionScore)
                                    .attemptCount(1)
                                    .createdAt(now)
                                    .bestAchievedAt(questionScore > 0 ? now : null)
                                    .build();
                    submissionMapper.insert(newSub);
                }

                itemResults.add(
                        PracticeObjectiveResultVO.ItemResult.builder()
                                .practiceQuestionId(pqId)
                                .questionId(pq.getQuestionId())
                                .correct(gradeResult.isCorrect())
                                .status(status)
                                .score(questionScore)
                                .fullScore(pq.getScore())
                                .userAnswer(submittedAnswer)
                                .correctAnswer(null)
                                .build());
            }
        }

        recalculateMemberScore(practice.getId(), userId);

        // 统计当前学生客观题汇总得分与整卷总分
        List<LessonPracticeSubmission> allUserSubs =
                submissionMapper.listByPracticeAndUser(practice.getId(), userId);
        Map<Long, LessonPracticeSubmission> userObjSubMap =
                allUserSubs.stream()
                        .filter(
                                s ->
                                        s.getPracticeQuestionId() != null
                                                && objMap.containsKey(s.getPracticeQuestionId()))
                        .collect(
                                Collectors.toMap(
                                        LessonPracticeSubmission::getPracticeQuestionId,
                                        s -> s,
                                        (a, b) -> a.getId() >= b.getId() ? a : b));

        int totalFull = objQuestions.stream().mapToInt(LessonPracticeQuestion::getScore).sum();
        int totalEarned =
                userObjSubMap.values().stream()
                        .mapToInt(s -> s.getScore() != null ? s.getScore() : 0)
                        .sum();
        int correctCount =
                (int)
                        userObjSubMap.values().stream()
                                .filter(s -> "ACCEPTED".equals(s.getStatus()))
                                .count();

        LessonPracticeMember updatedMember =
                memberMapper.findByPracticeAndUser(practice.getId(), userId);

        return PracticeObjectiveResultVO.builder()
                .totalScore(totalEarned)
                .fullScore(totalFull)
                .correctCount(correctCount)
                .totalCount(objQuestions.size())
                .myTotalScore(
                        updatedMember != null && updatedMember.getTotalScore() != null
                                ? updatedMember.getTotalScore()
                                : totalEarned)
                .mySolvedCount(
                        updatedMember != null && updatedMember.getSolvedCount() != null
                                ? updatedMember.getSolvedCount()
                                : correctCount)
                .items(itemResults)
                .build();
    }

    /** 实操题提交创建时（入 Redis 判题队列前）在同一事务内原子绑定课练记录，彻底消除快判竞态与断网丢单。 */
    @Transactional
    public boolean bindPracticalSubmissionOnCreate(
            String accessCode,
            Long userId,
            Long practiceQuestionId,
            Long questionId,
            Long submissionId,
            String questionType) {
        if (accessCode == null
                || accessCode.isBlank()
                || userId == null
                || practiceQuestionId == null
                || submissionId == null
                || questionType == null) {
            return false;
        }
        LessonPractice practice = practiceMapper.findByAccessCode(accessCode.trim().toUpperCase());
        if (practice == null || syncPracticeStatusIfExpired(practice)) {
            return false;
        }

        LessonPracticeMember member = memberMapper.lockByPracticeAndUser(practice.getId(), userId);
        if (member == null || Integer.valueOf(1).equals(member.getSubmitted())) {
            return false;
        }

        LessonPracticeQuestion pq = questionMapper.findById(practiceQuestionId);
        if (pq == null
                || !Objects.equals(practice.getId(), pq.getPracticeId())
                || !questionType.equalsIgnoreCase(pq.getQuestionType())
                || (questionId != null && !Objects.equals(questionId, pq.getQuestionId()))) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        LessonPracticeSubmission exist =
                submissionMapper.findByPracticeUserQuestionForUpdate(
                        practice.getId(), userId, practiceQuestionId);

        Long lpsId;
        if (exist != null) {
            if (!Objects.equals(exist.getSubmissionId(), submissionId)) {
                int attempts = (exist.getAttemptCount() != null ? exist.getAttemptCount() : 1) + 1;
                exist.setAttemptCount(attempts);
                exist.setSubmissionId(submissionId);
                int currentScore = exist.getScore() != null ? exist.getScore() : 0;
                if (currentScore == 0 && !"ACCEPTED".equals(exist.getStatus())) {
                    exist.setStatus("PENDING");
                    exist.setBestSubmissionId(submissionId);
                } else if (exist.getBestSubmissionId() == null) {
                    exist.setBestSubmissionId(submissionId);
                }
                submissionMapper.updateSubmission(exist);
            }
            lpsId = exist.getId();
        } else {
            LessonPracticeSubmission newSub =
                    LessonPracticeSubmission.builder()
                            .practiceId(practice.getId())
                            .practiceQuestionId(practiceQuestionId)
                            .userId(userId)
                            .questionType(questionType.toUpperCase())
                            .submissionId(submissionId)
                            .bestSubmissionId(submissionId)
                            .status("PENDING")
                            .score(0)
                            .attemptCount(1)
                            .createdAt(now)
                            .build();
            submissionMapper.insert(newSub);
            lpsId = newSub.getId();
        }

        recordBinding(questionType, submissionId, lpsId);
        return true;
    }

    private void recordBinding(String type, Long submissionId, Long practiceSubmissionId) {
        String key = bindingType(type);
        submissionMapper.insertBinding(key, submissionId, practiceSubmissionId);
        if (!Objects.equals(
                practiceSubmissionId, submissionMapper.lockBinding(key, submissionId))) {
            throw new BusinessException(ResultCode.CONFLICT, "该提交已关联其他课练作答");
        }
    }

    private String bindingType(String type) {
        String normalized = type.trim().toUpperCase(java.util.Locale.ROOT);
        return "DATABASE".equals(normalized) ? "DB" : normalized;
    }

    private String buildSubCtxKey(String questionType, Long submissionId) {
        return PRACTICE_SUB_CTX_PREFIX + questionType.trim().toUpperCase() + ":" + submissionId;
    }

    /**
     * 持久结算事件触发的判题完成回调。处理连续提交和乱序完成，保护已记录的最高分源码快照
     * bestSubmissionId 与首次达成时间 bestAchievedAt。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void onPracticalJudgeCompleted(String questionType, Long submissionId) {
        if (submissionId == null || questionType == null) {
            return;
        }
        String normType = bindingType(questionType);
        Long boundId = submissionMapper.findBinding(normType, submissionId);
        LessonPracticeSubmission sub =
                boundId != null
                        ? submissionMapper.findById(boundId)
                        : submissionMapper.findBySubmissionIdAndType(submissionId, normType);
        if (sub == null) {
            try {
                String lpsIdStr = redis.opsForValue().get(buildSubCtxKey(normType, submissionId));
                if (lpsIdStr != null && !lpsIdStr.isBlank()) {
                    sub = submissionMapper.findById(Long.valueOf(lpsIdStr.trim()));
                }
            } catch (Exception e) {
                log.debug("读取课练提交上下文缓存异常 subId={}: {}", submissionId, e.getMessage());
            }
        }
        if (sub == null) {
            return;
        }

        if (memberMapper.lockByPracticeAndUser(sub.getPracticeId(), sub.getUserId()) == null) {
            return;
        }
        sub = submissionMapper.findByIdForUpdate(sub.getId());
        if (sub == null) {
            return;
        }

        LessonPracticeQuestion pq = questionMapper.findById(sub.getPracticeQuestionId());
        int fullScore = pq != null && pq.getScore() != null ? pq.getScore() : 10;

        JudgeInfo info = extractJudgeInfo(normType, submissionId);
        if (info == null || isJudging(info.status())) {
            return;
        }

        applyPracticalJudgeOutcome(sub, submissionId, info.status(), info.score(), fullScore);
        recalculateMemberScore(sub.getPracticeId(), sub.getUserId());

        log.info(
                "课练评测自动结算完成: practiceId={}, userId={}, subId={}, bestSubId={}, score={}/{}",
                sub.getPracticeId(),
                sub.getUserId(),
                submissionId,
                sub.getBestSubmissionId(),
                sub.getScore(),
                fullScore);
    }

    /** 将编程/数据库实战提交与课练上下文关联并即刻统计（兼容前端直接上报，幂等防重计次）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordPracticalSubmission(
            String accessCode,
            Long userId,
            Long practiceQuestionId,
            Long submissionId,
            String questionType,
            String status,
            int score) {
        if (accessCode == null
                || accessCode.isBlank()
                || userId == null
                || practiceQuestionId == null) {
            return;
        }
        LessonPractice practice = practiceMapper.findByAccessCode(accessCode.trim().toUpperCase());
        if (practice == null || syncPracticeStatusIfExpired(practice)) {
            return;
        }

        LessonPracticeMember member = memberMapper.lockByPracticeAndUser(practice.getId(), userId);
        if (member == null || Integer.valueOf(1).equals(member.getSubmitted())) {
            return;
        }

        LessonPracticeQuestion pq = questionMapper.findById(practiceQuestionId);
        if (pq == null || !Objects.equals(practice.getId(), pq.getPracticeId())) {
            return;
        }
        String normType =
                questionType != null ? questionType.trim().toUpperCase() : pq.getQuestionType();
        int fullScore = pq.getScore() != null ? pq.getScore() : 10;

        Long boundId =
                submissionId == null
                        ? null
                        : submissionMapper.findBinding(bindingType(normType), submissionId);

        JudgeInfo info = null;
        if (submissionId != null) {
            info = verifyAndExtractJudgeInfo(normType, submissionId, userId, pq.getQuestionId());
            if (info == null) {
                return;
            }
        }

        String judgeStatus =
                info != null && info.status() != null
                        ? info.status()
                        : (status != null && "PENDING".equalsIgnoreCase(status)
                                ? "PENDING"
                                : "UNATTEMPTED");
        Integer judgeRawScore = info != null ? info.score() : 0;

        LessonPracticeSubmission exist =
                submissionMapper.findByPracticeUserQuestionForUpdate(
                        practice.getId(), userId, practiceQuestionId);

        if (boundId != null && (exist == null || !boundId.equals(exist.getId()))) {
            throw new BusinessException(ResultCode.CONFLICT, "该提交已关联其他课练作答");
        }
        boolean alreadyBound = boundId != null;
        Long practiceSubmissionId;
        if (exist != null) {
            practiceSubmissionId = exist.getId();
            boolean sameSub =
                    submissionId != null && Objects.equals(exist.getSubmissionId(), submissionId);
            if (!alreadyBound && !sameSub && submissionId != null) {
                int attempts = (exist.getAttemptCount() != null ? exist.getAttemptCount() : 1) + 1;
                exist.setAttemptCount(attempts);
                exist.setSubmissionId(submissionId);
            }
            if (!isJudging(judgeStatus) && !"UNATTEMPTED".equals(judgeStatus)) {
                applyPracticalJudgeOutcome(
                        exist, submissionId, judgeStatus, judgeRawScore, fullScore);
            } else if (!alreadyBound && !sameSub) {
                int currentScore = exist.getScore() != null ? exist.getScore() : 0;
                if (currentScore == 0 && !"ACCEPTED".equals(exist.getStatus())) {
                    exist.setStatus("PENDING");
                    if (submissionId != null) {
                        exist.setBestSubmissionId(submissionId);
                    }
                }
                submissionMapper.updateSubmission(exist);
            }
        } else {
            LocalDateTime now = LocalDateTime.now();
            int calculatedScore =
                    isJudging(judgeStatus)
                            ? 0
                            : calculateEarnedScore(judgeRawScore, fullScore, judgeStatus);
            String finalStatus =
                    isJudging(judgeStatus)
                            ? "PENDING"
                            : resolvePracticeStatus(judgeStatus, calculatedScore, fullScore);
            LessonPracticeSubmission newSub =
                    LessonPracticeSubmission.builder()
                            .practiceId(practice.getId())
                            .practiceQuestionId(practiceQuestionId)
                            .userId(userId)
                            .questionType(normType)
                            .submissionId(submissionId)
                            .bestSubmissionId(submissionId)
                            .status(finalStatus)
                            .score(calculatedScore)
                            .attemptCount(1)
                            .createdAt(now)
                            .bestAchievedAt(calculatedScore > 0 ? now : null)
                            .build();
            submissionMapper.insert(newSub);
            practiceSubmissionId = newSub.getId();
        }
        if (submissionId != null && !alreadyBound) {
            recordBinding(normType, submissionId, practiceSubmissionId);
        }

        recalculateMemberScore(practice.getId(), userId);
    }

    private void applyPracticalJudgeOutcome(
            LessonPracticeSubmission sub,
            Long submissionId,
            String judgeStatus,
            Integer judgeRawScore,
            int fullScore) {
        int earned = calculateEarnedScore(judgeRawScore, fullScore, judgeStatus);
        String resolvedStatus = resolvePracticeStatus(judgeStatus, earned, fullScore);

        int currentScore = sub.getScore() != null ? sub.getScore() : 0;
        boolean wasAccepted = "ACCEPTED".equals(sub.getStatus());
        boolean nowAccepted = "ACCEPTED".equals(resolvedStatus);
        boolean improved =
                earned > currentScore || (earned == currentScore && nowAccepted && !wasAccepted);

        int bestScore = Math.max(currentScore, earned);
        String bestStatus =
                (wasAccepted || nowAccepted)
                        ? "ACCEPTED"
                        : (bestScore > 0 ? "PARTIAL" : resolvedStatus);

        sub.setScore(bestScore);
        sub.setStatus(bestStatus);
        if (improved) {
            if (submissionId != null) {
                sub.setBestSubmissionId(submissionId);
            }
            if (earned > 0) {
                sub.setBestAchievedAt(LocalDateTime.now());
            }
        } else if (bestScore == 0 && !wasAccepted) {
            if (submissionId != null) {
                sub.setBestSubmissionId(submissionId);
            }
        } else if (sub.getBestSubmissionId() == null && submissionId != null) {
            sub.setBestSubmissionId(submissionId);
        }

        if (sub.getAttemptCount() == null || sub.getAttemptCount() < 1) {
            sub.setAttemptCount(1);
        }
        submissionMapper.updateSubmission(sub);
    }

    /** 重新计算成员总分与通过题目数，仅对已得分题目按真实达成时间 + 错误尝试次数统计罚时。 */
    private void recalculateMemberScore(Long practiceId, Long userId) {
        List<LessonPracticeSubmission> subs =
                submissionMapper.listByPracticeAndUser(practiceId, userId);

        Map<Long, LessonPracticeSubmission> bestMap = new HashMap<>();
        LocalDateTime lastActive = null;
        for (LessonPracticeSubmission s : subs) {
            if (s.getPracticeQuestionId() == null) {
                continue;
            }
            LocalDateTime activeTime =
                    s.getUpdatedAt() != null ? s.getUpdatedAt() : s.getCreatedAt();
            if (activeTime != null && (lastActive == null || activeTime.isAfter(lastActive))) {
                lastActive = activeTime;
            }
            bestMap.merge(
                    s.getPracticeQuestionId(),
                    s,
                    (existing, current) -> {
                        int eScore = existing.getScore() != null ? existing.getScore() : 0;
                        int cScore = current.getScore() != null ? current.getScore() : 0;
                        if (cScore > eScore) return current;
                        if (cScore == eScore && "ACCEPTED".equals(current.getStatus()))
                            return current;
                        return existing;
                    });
        }

        int totalScore =
                bestMap.values().stream()
                        .mapToInt(s -> s.getScore() != null ? s.getScore() : 0)
                        .sum();
        int solvedCount =
                (int)
                        bestMap.values().stream()
                                .filter(s -> "ACCEPTED".equals(s.getStatus()))
                                .count();

        LessonPractice practice = practiceMapper.findById(practiceId);
        long penalty = 0L;
        if (practice != null && practice.getStartTime() != null) {
            for (LessonPracticeSubmission s : bestMap.values()) {
                int qScore = s.getScore() != null ? s.getScore() : 0;
                if (qScore > 0 || "ACCEPTED".equals(s.getStatus())) {
                    LocalDateTime achievedAt =
                            s.getBestAchievedAt() != null
                                    ? s.getBestAchievedAt()
                                    : s.getCreatedAt();
                    if (achievedAt != null) {
                        penalty +=
                                Math.max(
                                        0L,
                                        Duration.between(practice.getStartTime(), achievedAt)
                                                .getSeconds());
                    }
                    if (!"OBJECTIVE".equals(s.getQuestionType())) {
                        int attempts = s.getAttemptCount() != null ? s.getAttemptCount() : 1;
                        if (attempts > 1) {
                            penalty += (long) (attempts - 1) * 1200L;
                        }
                    }
                }
            }
        }

        memberMapper.updateScoreAndSolvedWithPenalty(
                practiceId,
                userId,
                totalScore,
                solvedCount,
                penalty,
                lastActive != null ? lastActive : LocalDateTime.now());
    }

    /** 校验指定用户是否可通过已加入（或可管理）的课练访问隐藏题目（status != 1）。 */
    public boolean canUserAccessQuestionViaPractice(
            String practiceCode, Long userId, String questionType, Long questionId) {
        if (practiceCode == null
                || practiceCode.isBlank()
                || userId == null
                || questionType == null
                || questionId == null) {
            return false;
        }
        LessonPractice practice =
                practiceMapper.findByAccessCode(practiceCode.trim().toUpperCase());
        if (practice == null) {
            return false;
        }
        boolean canManage = canManagePractice(practice, userId);
        LessonPracticeMember member = memberMapper.findByPracticeAndUser(practice.getId(), userId);
        if (!canManage && member == null) {
            return false;
        }
        return questionMapper.existsPracticeQuestion(
                practice.getId(), questionType.trim().toUpperCase(), questionId);
    }

    private record JudgeInfo(String status, Integer score) {}

    private JudgeInfo verifyAndExtractJudgeInfo(
            String questionType, Long submissionId, Long expectedUserId, Long expectedQuestionId) {
        return strategyFactory
                .findStrategy(questionType)
                .map(
                        s ->
                                s.verifyAndExtractJudgeInfo(
                                        submissionId, expectedUserId, expectedQuestionId))
                .map(r -> new JudgeInfo(r.status(), r.score()))
                .orElse(null);
    }

    private JudgeInfo extractJudgeInfo(String questionType, Long submissionId) {
        if (submissionId == null) return null;
        return strategyFactory
                .findStrategy(questionType)
                .map(s -> s.extractJudgeInfo(submissionId))
                .map(r -> new JudgeInfo(r.status(), r.score()))
                .orElse(null);
    }

    private int calculateEarnedScore(Integer rawJudgeScore, int fullScore, String status) {
        if ("ACCEPTED".equals(status)) {
            return fullScore;
        }
        if (rawJudgeScore == null || rawJudgeScore <= 0) {
            return 0;
        }
        int earned = (int) Math.round((double) rawJudgeScore * fullScore / 100.0);
        if (earned == 0 && fullScore > 0) {
            earned = 1;
        }
        return Math.min(earned, fullScore);
    }

    private String resolvePracticeStatus(String judgeStatus, int earnedScore, int fullScore) {
        if (earnedScore >= fullScore || "ACCEPTED".equals(judgeStatus)) {
            return "ACCEPTED";
        }
        if (earnedScore > 0) {
            return "PARTIAL";
        }
        return judgeStatus != null && !judgeStatus.isBlank() ? judgeStatus : "WRONG_ANSWER";
    }

    private boolean isJudging(String status) {
        return "PENDING".equals(status) || "COMPILING".equals(status) || "JUDGING".equals(status);
    }

    /** 学生主动交卷。 */
    @Transactional
    public void submitPractice(String accessCode, Long userId) {
        LessonPractice practice = practiceMapper.findByAccessCode(accessCode.trim().toUpperCase());
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        syncPracticeStatusIfExpired(practice);
        LessonPracticeMember member = memberMapper.findByPracticeAndUser(practice.getId(), userId);
        if (member == null) {
            if (canManagePractice(practice, userId)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "当前为教师管理预览视角，无需交卷");
            }
            throw new BusinessException(ResultCode.FORBIDDEN, "您尚未加入本次课练");
        }
        memberMapper.markSubmitted(practice.getId(), userId, LocalDateTime.now());
    }

    /** 获取正统实时成绩榜 (Scoreboard)，校验参练资格或管理权限。 */
    public PracticeScoreboardVO getScoreboard(String accessCode, Long currentUserId) {
        String code = accessCode.trim().toUpperCase();
        LessonPractice practice = practiceMapper.findByAccessCode(code);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        syncPracticeStatusIfExpired(practice);

        boolean canManage = canManagePractice(practice, currentUserId);
        LessonPracticeMember myMember =
                currentUserId != null
                        ? memberMapper.findByPracticeAndUser(practice.getId(), currentUserId)
                        : null;
        if (!canManage && myMember == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "请先输入 6 位课堂口令解锁入场");
        }

        return buildScoreboardInternal(practice, currentUserId);
    }

    private PracticeScoreboardVO buildScoreboardInternal(
            LessonPractice practice, Long currentUserId) {
        LocalDateTime now = LocalDateTime.now();
        Long remainingSeconds = null;
        if ("ENDED".equals(practice.getStatus())) {
            remainingSeconds = 0L;
        } else if (practice.getEndTime() != null) {
            remainingSeconds =
                    Math.max(0, Duration.between(now, practice.getEndTime()).getSeconds());
        }

        List<LessonPracticeQuestion> questions = questionMapper.listByPracticeId(practice.getId());
        List<PracticeScoreboardVO.QuestionHeader> headers =
                questions.stream()
                        .map(
                                q ->
                                        PracticeScoreboardVO.QuestionHeader.builder()
                                                .practiceQuestionId(q.getId())
                                                .questionId(q.getQuestionId())
                                                .questionType(q.getQuestionType())
                                                .title(q.getTitle())
                                                .score(q.getScore())
                                                .sortOrder(q.getSortOrder())
                                                .build())
                        .toList();

        List<LessonPracticeMember> members = memberMapper.listScoreboard(practice.getId());
        Set<Long> userIds =
                members.stream().map(LessonPracticeMember::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap =
                userIds.isEmpty()
                        ? Map.of()
                        : userMapper.findByIds(userIds).stream()
                                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<PracticeScoreboardVO.QuestionStatBenchmark> benchmarks = new ArrayList<>();
        for (LessonPracticeQuestion q : questions) {
            int subCount = submissionMapper.countSubmitByPracticeQuestionId(q.getId());
            int passCount = submissionMapper.countPassByPracticeQuestionId(q.getId());
            double rate = subCount > 0 ? (double) passCount / subCount * 100 : 0.0;
            benchmarks.add(
                    PracticeScoreboardVO.QuestionStatBenchmark.builder()
                            .practiceQuestionId(q.getId())
                            .submitUserCount(subCount)
                            .passUserCount(passCount)
                            .passRate(Math.round(rate * 10.0) / 10.0)
                            .build());
        }

        List<PracticeScoreboardVO.RankRow> rows = new ArrayList<>();
        int rank = 1;
        for (LessonPracticeMember m : members) {
            User u = userMap.get(m.getUserId());
            List<LessonPracticeSubmission> userSubs =
                    submissionMapper.listByPracticeAndUser(practice.getId(), m.getUserId());
            Map<Long, LessonPracticeSubmission> subByPqId =
                    userSubs.stream()
                            .filter(s -> s.getPracticeQuestionId() != null)
                            .collect(
                                    Collectors.toMap(
                                            LessonPracticeSubmission::getPracticeQuestionId,
                                            s -> s,
                                            (a, b) -> a.getId() >= b.getId() ? a : b));

            List<PracticeScoreboardVO.CellState> cellStates = new ArrayList<>();
            LocalDateTime lastSubTime = null;
            for (LessonPracticeQuestion q : questions) {
                LessonPracticeSubmission sub = subByPqId.get(q.getId());
                if (sub != null) {
                    LocalDateTime activeTime =
                            sub.getUpdatedAt() != null ? sub.getUpdatedAt() : sub.getCreatedAt();
                    if (activeTime != null
                            && (lastSubTime == null || activeTime.isAfter(lastSubTime))) {
                        lastSubTime = activeTime;
                    }
                    LocalDateTime refTime =
                            sub.getBestAchievedAt() != null ? sub.getBestAchievedAt() : activeTime;
                    long elapsed = 0L;
                    if (practice.getStartTime() != null && refTime != null) {
                        elapsed =
                                Math.max(
                                        1,
                                        Duration.between(practice.getStartTime(), refTime)
                                                .toMinutes());
                    }
                    int attempts =
                            sub.getAttemptCount() != null && sub.getAttemptCount() > 0
                                    ? sub.getAttemptCount()
                                    : 1;
                    cellStates.add(
                            PracticeScoreboardVO.CellState.builder()
                                    .practiceQuestionId(q.getId())
                                    .status(sub.getStatus())
                                    .score(sub.getScore() != null ? sub.getScore() : 0)
                                    .attempts(attempts)
                                    .elapsedMinutes(elapsed)
                                    .build());
                } else {
                    cellStates.add(
                            PracticeScoreboardVO.CellState.builder()
                                    .practiceQuestionId(q.getId())
                                    .status("UNATTEMPTED")
                                    .score(0)
                                    .attempts(0)
                                    .elapsedMinutes(0L)
                                    .build());
                }
            }

            String uname = u != null ? u.getUsername() : "学生" + m.getUserId();
            rows.add(
                    PracticeScoreboardVO.RankRow.builder()
                            .rank(rank++)
                            .userId(m.getUserId())
                            .username(uname)
                            .nickname(uname)
                            .totalScore(m.getTotalScore() != null ? m.getTotalScore() : 0)
                            .solvedCount(m.getSolvedCount() != null ? m.getSolvedCount() : 0)
                            .penaltyTime(m.getPenaltyTime() != null ? m.getPenaltyTime() : 0L)
                            .submitted(m.getSubmitted() == 1)
                            .lastSubmitTime(lastSubTime)
                            .isCurrentUser(Objects.equals(m.getUserId(), currentUserId))
                            .cellStates(cellStates)
                            .build());
        }

        return PracticeScoreboardVO.builder()
                .practiceId(practice.getId())
                .title(practice.getTitle())
                .accessCode(practice.getAccessCode())
                .status(practice.getStatus())
                .remainingSeconds(remainingSeconds)
                .questionHeaders(headers)
                .benchmarks(benchmarks)
                .rows(rows)
                .build();
    }

    /** 教师后台获取考务总览大盘数据 (Overview)。 */
    public PracticeOverviewVO getOverview(Long practiceId, Long teacherId) {
        LessonPractice practice = practiceMapper.findById(practiceId);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        checkPracticeManagePermission(practice, teacherId);
        syncPracticeStatusIfExpired(practice);

        User teacher = userMapper.findById(practice.getTeacherId());
        int joinedCount = memberMapper.countByPracticeId(practice.getId());
        int submittedCount = memberMapper.countSubmittedByPracticeId(practice.getId());
        double submitRate = joinedCount > 0 ? (double) submittedCount / joinedCount * 100 : 0.0;

        List<LessonPracticeMember> members = memberMapper.listByPracticeId(practice.getId());
        double avgScore =
                members.isEmpty()
                        ? 0.0
                        : members.stream()
                                .mapToInt(m -> m.getTotalScore() != null ? m.getTotalScore() : 0)
                                .average()
                                .orElse(0.0);
        int maxScore =
                members.stream()
                        .mapToInt(m -> m.getTotalScore() != null ? m.getTotalScore() : 0)
                        .max()
                        .orElse(0);
        int minScore =
                members.stream()
                        .mapToInt(m -> m.getTotalScore() != null ? m.getTotalScore() : 0)
                        .min()
                        .orElse(0);

        List<LessonPracticeQuestion> questions = questionMapper.listByPracticeId(practice.getId());
        int totalPossibleScore =
                questions.stream().mapToInt(LessonPracticeQuestion::getScore).sum();
        int perfectCount =
                (int)
                        members.stream()
                                .filter(
                                        m ->
                                                m.getTotalScore() != null
                                                        && m.getTotalScore() >= totalPossibleScore
                                                        && totalPossibleScore > 0)
                                .count();
        int passCount =
                (int)
                        members.stream()
                                .filter(
                                        m ->
                                                m.getTotalScore() != null
                                                        && m.getTotalScore()
                                                                >= (totalPossibleScore * 0.6))
                                .count();
        double passRate = members.isEmpty() ? 0.0 : (double) passCount / members.size() * 100;

        int totalSubmissions = submissionMapper.sumAttemptsByPracticeId(practice.getId());

        LocalDateTime now = LocalDateTime.now();
        Long remainingSeconds = null;
        if ("ENDED".equals(practice.getStatus())) {
            remainingSeconds = 0L;
        } else if (practice.getEndTime() != null) {
            remainingSeconds =
                    Math.max(0, Duration.between(now, practice.getEndTime()).getSeconds());
        }

        List<PracticeOverviewVO.QuestionAccuracyItem> accuracies = new ArrayList<>();
        for (LessonPracticeQuestion q : questions) {
            int subCount = submissionMapper.countSubmitByPracticeQuestionId(q.getId());
            int pass = submissionMapper.countPassByPracticeQuestionId(q.getId());
            double rate = subCount > 0 ? (double) pass / subCount * 100 : 0.0;
            accuracies.add(
                    PracticeOverviewVO.QuestionAccuracyItem.builder()
                            .practiceQuestionId(q.getId())
                            .questionId(q.getQuestionId())
                            .questionType(q.getQuestionType())
                            .subType(resolveQuestionSubType(q))
                            .title(q.getTitle())
                            .fullScore(q.getScore())
                            .submitCount(subCount)
                            .passCount(pass)
                            .passRate(Math.round(rate * 10.0) / 10.0)
                            .build());
        }

        Set<Long> userIds =
                members.stream().map(LessonPracticeMember::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap =
                userIds.isEmpty()
                        ? Map.of()
                        : userMapper.findByIds(userIds).stream()
                                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<PracticeOverviewVO.StudentRosterItem> students = new ArrayList<>();
        for (LessonPracticeMember m : members) {
            User u = userMap.get(m.getUserId());
            String uname = u != null ? u.getUsername() : "学生" + m.getUserId();
            students.add(
                    PracticeOverviewVO.StudentRosterItem.builder()
                            .userId(m.getUserId())
                            .username(uname)
                            .nickname(uname)
                            .totalScore(m.getTotalScore())
                            .solvedCount(m.getSolvedCount())
                            .submitted(m.getSubmitted() == 1)
                            .joinedAt(m.getJoinedAt())
                            .submittedAt(m.getSubmittedAt())
                            .lastActiveAt(m.getLastActiveAt())
                            .build());
        }

        PracticeOverviewVO.MetricsCard metrics =
                PracticeOverviewVO.MetricsCard.builder()
                        .joinedCount(joinedCount)
                        .submittedCount(submittedCount)
                        .submitRate(Math.round(submitRate * 10.0) / 10.0)
                        .avgScore(Math.round(avgScore * 10.0) / 10.0)
                        .maxScore(maxScore)
                        .minScore(minScore)
                        .totalSubmissions(totalSubmissions)
                        .perfectScoreCount(perfectCount)
                        .activeJudgingCount(0)
                        .passCount(passCount)
                        .passRate(Math.round(passRate * 10.0) / 10.0)
                        .build();

        return PracticeOverviewVO.builder()
                .practiceId(practice.getId())
                .title(practice.getTitle())
                .description(practice.getDescription())
                .accessCode(practice.getAccessCode())
                .durationMinutes(practice.getDurationMinutes())
                .status(practice.getStatus())
                .teacherId(practice.getTeacherId())
                .teacherName(teacher != null ? teacher.getUsername() : "教师")
                .startTime(practice.getStartTime())
                .endTime(practice.getEndTime())
                .remainingSeconds(remainingSeconds)
                .metrics(metrics)
                .questionAccuracies(accuracies)
                .students(students)
                .build();
    }

    private String resolveQuestionSubType(LessonPracticeQuestion q) {
        if (q == null || q.getQuestionType() == null) {
            return null;
        }
        String type = q.getQuestionType().trim().toUpperCase();
        try {
            if ("OBJECTIVE".equals(type)) {
                ObjectiveQuestion oq = objectiveQuestionMapper.findById(q.getQuestionId());
                return oq != null ? oq.getType() : "SINGLE";
            } else if ("ALGO".equals(type)) {
                Question aq = questionBaseMapper.findById(q.getQuestionId());
                return aq != null ? aq.getDifficulty() : "MEDIUM";
            } else if ("DB".equals(type)) {
                DbQuestion dq = dbQuestionMapper.findById(q.getQuestionId());
                return dq != null ? dq.getType() : "SELECT";
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 教师调阅指定学生全息作答代码与判题详情审计（读取 bestSubmissionId 并自动解包 JSON 代码）。 */
    public StudentAuditDetailVO getStudentAudit(
            Long practiceId, Long targetUserId, Long teacherId) {
        LessonPractice practice = practiceMapper.findById(practiceId);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        checkPracticeManagePermission(practice, teacherId);

        LessonPracticeMember member = memberMapper.findByPracticeAndUser(practiceId, targetUserId);
        if (member == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "该学生未参与本次课练");
        }
        User user = userMapper.findById(targetUserId);

        List<LessonPracticeQuestion> questions = questionMapper.listByPracticeId(practiceId);
        List<LessonPracticeSubmission> subs =
                submissionMapper.listByPracticeAndUser(practiceId, targetUserId);
        Map<Long, LessonPracticeSubmission> subByPqId =
                subs.stream()
                        .filter(s -> s.getPracticeQuestionId() != null)
                        .collect(
                                Collectors.toMap(
                                        LessonPracticeSubmission::getPracticeQuestionId,
                                        s -> s,
                                        (a, b) -> a.getId() >= b.getId() ? a : b));

        List<StudentAuditDetailVO.AuditQuestionItem> items = new ArrayList<>();
        for (LessonPracticeQuestion pq : questions) {
            LessonPracticeSubmission sub = subByPqId.get(pq.getId());
            LocalDateTime auditTime =
                    sub != null
                            ? (sub.getBestAchievedAt() != null
                                    ? sub.getBestAchievedAt()
                                    : (sub.getUpdatedAt() != null
                                            ? sub.getUpdatedAt()
                                            : sub.getCreatedAt()))
                            : null;
            StudentAuditDetailVO.AuditQuestionItem.AuditQuestionItemBuilder builder =
                    StudentAuditDetailVO.AuditQuestionItem.builder()
                            .practiceQuestionId(pq.getId())
                            .questionId(pq.getQuestionId())
                            .questionType(pq.getQuestionType())
                            .title(pq.getTitle())
                            .fullScore(pq.getScore())
                            .earnedScore(sub != null && sub.getScore() != null ? sub.getScore() : 0)
                            .status(sub != null ? sub.getStatus() : "UNATTEMPTED")
                            .submittedAt(auditTime);

            Long targetSubId =
                    sub != null
                            ? (sub.getBestSubmissionId() != null
                                    ? sub.getBestSubmissionId()
                                    : sub.getSubmissionId())
                            : null;

            if ("OBJECTIVE".equals(pq.getQuestionType())) {
                ObjectiveQuestion oq = objectiveQuestionMapper.findById(pq.getQuestionId());
                if (oq != null) {
                    builder.objectiveType(oq.getType())
                            .objectiveContent(oq.getContent())
                            .objectiveOptions(parseOptions(oq.getOptions()))
                            .correctAnswer(oq.getAnswer());
                }
                builder.userAnswer(sub != null ? sub.getAnswer() : null);
            } else if ("ALGO".equals(pq.getQuestionType())) {
                if (targetSubId != null) {
                    Submission s = submissionRecordMapper.findById(targetSubId);
                    if (s != null) {
                        builder.submissionId(s.getId())
                                .code(extractReadableCode(s.getCodeContent()))
                                .language(s.getLanguage())
                                .timeMs(s.getTimeUsedMs())
                                .memoryKb(s.getMemoryUsedKb())
                                .judgeResultJson(s.getResultJson());
                    }
                }
            } else if ("DB".equals(pq.getQuestionType())) {
                if (targetSubId != null) {
                    var s = dbSubmissionMapper.findById(targetSubId);
                    if (s != null) {
                        builder.submissionId(s.getId())
                                .code(s.getSqlContent())
                                .language("sql")
                                .timeMs(s.getTimeUsedMs())
                                .judgeResultJson(s.getResultJson());
                    }
                }
            }
            items.add(builder.build());
        }

        String uname = user != null ? user.getUsername() : "学生" + targetUserId;
        return StudentAuditDetailVO.builder()
                .userId(targetUserId)
                .username(uname)
                .nickname(uname)
                .totalScore(member.getTotalScore())
                .solvedCount(member.getSolvedCount())
                .submitted(member.getSubmitted() == 1)
                .joinedAt(member.getJoinedAt())
                .submittedAt(member.getSubmittedAt())
                .items(items)
                .build();
    }

    private String extractReadableCode(String rawCodeContent) {
        if (rawCodeContent == null || rawCodeContent.isBlank()) {
            return "";
        }
        String trimmed = rawCodeContent.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, String> codeMap =
                        objectMapper.readValue(
                                trimmed, new TypeReference<Map<String, String>>() {});
                if (codeMap != null && !codeMap.isEmpty()) {
                    if (codeMap.size() == 1) {
                        String val = codeMap.values().iterator().next();
                        return val != null ? val : "";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> entry : codeMap.entrySet()) {
                        if (sb.length() > 0) {
                            sb.append("\n\n");
                        }
                        sb.append("// === ").append(entry.getKey()).append(" ===\n");
                        sb.append(entry.getValue() != null ? entry.getValue() : "");
                    }
                    return sb.toString();
                }
            } catch (Exception ignored) {
            }
        }
        return rawCodeContent;
    }

    /** 导出全班成绩单 CSV（带 UTF-8 BOM，Excel 兼容不乱码）。 */
    public void exportScoreboardCsv(Long practiceId, Long teacherId, HttpServletResponse response) {
        LessonPractice practice = practiceMapper.findById(practiceId);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        checkPracticeManagePermission(practice, teacherId);
        syncPracticeStatusIfExpired(practice);

        PracticeScoreboardVO scoreboard = buildScoreboardInternal(practice, teacherId);

        try {
            String filename =
                    "practice_scores_"
                            + practice.getAccessCode()
                            + "_"
                            + System.currentTimeMillis()
                            + ".csv";
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=" + filename);

            OutputStream os = response.getOutputStream();
            os.write(0xEF);
            os.write(0xBB);
            os.write(0xBF);

            PrintWriter writer = new PrintWriter(os, false, StandardCharsets.UTF_8);

            StringBuilder headerSb = new StringBuilder();
            headerSb.append("排名,参练学生(账号),实得总分,攻克题数(AC),交卷状态,最终提交时间");
            for (PracticeScoreboardVO.QuestionHeader q : scoreboard.getQuestionHeaders()) {
                headerSb.append(",").append(escapeCsv(q.getTitle() + " (" + q.getScore() + "分)"));
            }
            writer.println(headerSb);

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (PracticeScoreboardVO.RankRow row : scoreboard.getRows()) {
                StringBuilder rowSb = new StringBuilder();
                rowSb.append(row.getRank()).append(",");
                rowSb.append(escapeCsv(row.getUsername())).append(",");
                rowSb.append(row.getTotalScore()).append(",");
                rowSb.append(row.getSolvedCount()).append(",");
                rowSb.append(Boolean.TRUE.equals(row.getSubmitted()) ? "已交卷" : "答题中").append(",");
                rowSb.append(
                        row.getLastSubmitTime() != null
                                ? row.getLastSubmitTime().format(dtf)
                                : "-");

                Map<Long, PracticeScoreboardVO.CellState> cellMap =
                        row.getCellStates().stream()
                                .collect(
                                        Collectors.toMap(
                                                PracticeScoreboardVO.CellState
                                                        ::getPracticeQuestionId,
                                                c -> c,
                                                (a, b) -> a));
                for (PracticeScoreboardVO.QuestionHeader q : scoreboard.getQuestionHeaders()) {
                    PracticeScoreboardVO.CellState cell = cellMap.get(q.getPracticeQuestionId());
                    String cellVal =
                            cell != null && !"UNATTEMPTED".equals(cell.getStatus())
                                    ? cell.getScore()
                                            + "分 ("
                                            + cell.getStatus()
                                            + ", 尝试"
                                            + cell.getAttempts()
                                            + "次)"
                                    : "未作答";
                    rowSb.append(",").append(escapeCsv(cellVal));
                }
                writer.println(rowSb);
            }

            writer.flush();
        } catch (Exception e) {
            log.error("导出成绩单失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "导出成绩单失败: " + e.getMessage());
        }
    }

    private String escapeCsv(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }

    private void checkPracticeManagePermission(LessonPractice practice, Long teacherId) {
        if (teacherId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!canManagePractice(practice, teacherId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权管理该课练");
        }
    }

    /** 教师提前结束课练（主动收卷时记录实际结束时间）。 */
    @Transactional
    public void endPractice(String accessCode, Long teacherId) {
        LessonPractice practice = practiceMapper.findByAccessCode(accessCode.trim().toUpperCase());
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        checkPracticeManagePermission(practice, teacherId);
        practiceMapper.updateStatusAndEndTime(practice.getId(), "ENDED", LocalDateTime.now());
    }

    /** 教师延长课练时长（以 max(当前时间, 原截止时间) 为基准顺延，若已过期则自动恢复为 ACTIVE）。 */
    @Transactional
    public void extendDuration(String accessCode, int extraMinutes, Long teacherId) {
        if (extraMinutes <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "加时分钟数必须大于 0");
        }
        LessonPractice practice = practiceMapper.findByAccessCode(accessCode.trim().toUpperCase());
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        checkPracticeManagePermission(practice, teacherId);

        LocalDateTime now = LocalDateTime.now();
        int baseDuration =
                practice.getDurationMinutes() != null ? practice.getDurationMinutes() : 0;
        int newDuration = baseDuration + extraMinutes;
        LocalDateTime baseEnd =
                (practice.getEndTime() != null && practice.getEndTime().isAfter(now))
                        ? practice.getEndTime()
                        : now;
        LocalDateTime newEndTime = baseEnd.plusMinutes(extraMinutes);
        String newStatus = newEndTime.isAfter(now) ? "ACTIVE" : practice.getStatus();

        practiceMapper.updateDurationAndStatus(
                practice.getId(), newDuration, newEndTime, newStatus);
    }

    /** 教师或管理员删除课练。 */
    @Transactional
    public void deletePractice(Long id, AuthUser user) {
        LessonPractice practice = practiceMapper.findById(id);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        boolean isAdmin = user != null && user.hasRole(Role.ADMIN);
        boolean isOwner = user != null && Objects.equals(practice.getTeacherId(), user.getId());
        if (!isAdmin && !isOwner) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权删除该课练");
        }

        submissionMapper.deleteByPracticeId(id);
        memberMapper.deleteByPracticeId(id);
        questionMapper.deleteByPracticeId(id);
        practiceMapper.deleteById(id);
    }

    /** 兼容旧签名的分页查询。 */
    public Map<String, Object> listPractices(
            String keyword, String status, Long teacherId, Long currentUserId, int page, int size) {
        return listPractices(keyword, status, teacherId, currentUserId, false, false, page, size);
    }

    /** 分页查询课练列表：查询前先自动收口已到期课练，支持口令按权限脱敏及「已参与」服务端过滤。 */
    public Map<String, Object> listPractices(
            String keyword,
            String status,
            Long teacherId,
            Long currentUserId,
            boolean isAdminView,
            boolean joinedOnly,
            int page,
            int size) {
        practiceMapper.closeExpiredPractices();

        int offset = Math.max(0, (page - 1) * size);
        String kw = keyword != null && !keyword.isBlank() ? keyword.trim() : null;
        String st = status != null && !status.isBlank() ? status.trim() : null;
        boolean isCurrentAdmin = isAdminUser(currentUserId);

        List<PracticeSummaryVO> voList =
                practiceMapper.listFilterAggregated(
                        kw,
                        st,
                        teacherId,
                        currentUserId,
                        isAdminView,
                        isCurrentAdmin,
                        joinedOnly,
                        offset,
                        size);
        int total =
                practiceMapper.countFilter(
                        kw, st, teacherId, currentUserId, isAdminView, joinedOnly);

        return Map.of("list", voList, "total", total, "page", page, "size", size);
    }

    private PracticeSummaryVO getSummaryVO(LessonPractice p, Long currentUserId) {
        User teacher = userMapper.findById(p.getTeacherId());
        String teacherName = teacher != null ? teacher.getUsername() : "教师";
        int memberCount = memberMapper.countByPracticeId(p.getId());

        List<LessonPracticeQuestion> qList = questionMapper.listByPracticeId(p.getId());
        int objectiveCount =
                (int) qList.stream().filter(q -> "OBJECTIVE".equals(q.getQuestionType())).count();
        int practicalCount = qList.size() - objectiveCount;

        LessonPracticeMember member =
                currentUserId != null
                        ? memberMapper.findByPracticeAndUser(p.getId(), currentUserId)
                        : null;

        boolean canSeeCode = canManagePractice(p, currentUserId) || member != null;

        return PracticeSummaryVO.builder()
                .id(p.getId())
                .title(p.getTitle())
                .description(p.getDescription())
                .accessCode(canSeeCode ? p.getAccessCode() : null)
                .durationMinutes(p.getDurationMinutes())
                .status(p.getStatus())
                .teacherId(p.getTeacherId())
                .teacherName(teacherName)
                .memberCount(memberCount)
                .questionCount(qList.size())
                .objectiveCount(objectiveCount)
                .practicalCount(practicalCount)
                .startTime(p.getStartTime())
                .endTime(p.getEndTime())
                .myJoined(member != null)
                .myScore(member != null ? member.getTotalScore() : null)
                .mySubmitted(member != null && member.getSubmitted() == 1)
                .createdAt(p.getCreatedAt())
                .build();
    }

    private List<String> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
