package com.kece.practice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.objective.dto.AnswerResultVO;
import com.kece.objective.entity.ObjectiveQuestion;
import com.kece.objective.mapper.ObjectiveQuestionMapper;
import com.kece.objective.service.ObjectiveGrader;
import com.kece.practice.dto.*;
import com.kece.practice.entity.*;
import com.kece.practice.mapper.*;
import com.kece.question.strategy.QuestionMeta;
import com.kece.question.strategy.QuestionStrategyFactory;
import com.kece.question.strategy.QuestionTypeStrategy;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LessonPracticeService {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LessonPracticeMapper practiceMapper;
    private final LessonPracticeQuestionMapper questionMapper;
    private final LessonPracticeMemberMapper memberMapper;
    private final LessonPracticeSubmissionMapper submissionMapper;
    private final ObjectiveQuestionMapper objectiveQuestionMapper;
    private final UserMapper userMapper;
    private final ObjectiveGrader objectiveGrader;
    private final ObjectMapper objectMapper;
    private final QuestionStrategyFactory strategyFactory;

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
        LocalDateTime endTime =
                req.getDurationMinutes() != null && req.getDurationMinutes() > 0
                        ? now.plusMinutes(req.getDurationMinutes())
                        : null;

        LessonPractice practice =
                LessonPractice.builder()
                        .title(req.getTitle().trim())
                        .description(req.getDescription())
                        .accessCode(accessCode)
                        .durationMinutes(req.getDurationMinutes())
                        .status("ACTIVE")
                        .teacherId(teacherId)
                        .startTime(now)
                        .endTime(endTime)
                        .build();

        practiceMapper.insert(practice);

        if (req.getQuestions() != null && !req.getQuestions().isEmpty()) {
            AuthUser user = AuthContextHolder.get();
            boolean isAdmin = user != null && user.hasRole(Role.ADMIN);
            List<LessonPracticeQuestion> qList = new ArrayList<>();
            int sort = 1;
            for (PracticeQuestionItem item : req.getQuestions()) {
                String type =
                        item.getQuestionType() != null ? item.getQuestionType().toUpperCase() : "";
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
            questionMapper.insertBatch(qList);
        }

        return getSummaryVO(practice, teacherId);
    }

    /** 学生或教师通过 6 位口令加入课练房间。 */
    @Transactional
    public PracticeDetailVO joinPractice(Long userId, String accessCode) {
        String code = accessCode.trim().toUpperCase();
        LessonPractice practice = practiceMapper.findByAccessCode(code);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练口令不存在，请核对后重试");
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
                            .joinedAt(LocalDateTime.now())
                            .submitted(0)
                            .build();
            memberMapper.insert(member);
        }

        return getPracticeDetail(code, userId);
    }

    /** 获取课练房间主页（题目列表与作答概览）。 */
    public PracticeDetailVO getPracticeDetail(String accessCode, Long userId) {
        String code = accessCode.trim().toUpperCase();
        LessonPractice practice = practiceMapper.findByAccessCode(code);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练口令不存在");
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
            if (remainingSeconds <= 0 && "ACTIVE".equals(practice.getStatus())) {
                practice.setStatus("ENDED");
                practiceMapper.updateStatusAndEndTime(practice.getId(), "ENDED", now);
                remainingSeconds = 0L;
            }
        }

        LessonPracticeMember myMember =
                userId != null
                        ? memberMapper.findByPracticeAndUser(practice.getId(), userId)
                        : null;
        if ("ENDED".equals(practice.getStatus())) {
            User currentUser = userId != null ? userMapper.findById(userId) : null;
            boolean isTeacherOrAdmin =
                    currentUser != null
                            && (practice.getTeacherId().equals(userId)
                                    || (currentUser.getRoles() != null
                                            && (currentUser.getRoles().contains("ADMIN")
                                                    || currentUser
                                                            .getRoles()
                                                            .contains("TEACHER"))));
            boolean isJoined = myMember != null;
            if (!isTeacherOrAdmin && !isJoined) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "该课练已结束，未参练人员无法进入");
            }
        }
        List<LessonPracticeQuestion> allQuestions =
                questionMapper.listByPracticeId(practice.getId());

        if (myMember != null) {
            syncAndRecalculateMember(practice, userId, allQuestions);
            myMember = memberMapper.findByPracticeAndUser(practice.getId(), userId);
        }

        List<LessonPracticeSubmission> mySubmissions =
                userId != null
                        ? submissionMapper.listByPracticeAndUser(practice.getId(), userId)
                        : Collections.emptyList();

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
                boolean isCorrect = sub != null && "ACCEPTED".equals(sub.getStatus());

                objectiveList.add(
                        PracticeObjectiveQuestionVO.builder()
                                .practiceQuestionId(pq.getId())
                                .questionId(pq.getQuestionId())
                                .type(oq != null ? oq.getType() : "SINGLE")
                                .content(oq != null ? oq.getContent() : pq.getTitle())
                                .options(options)
                                .score(pq.getScore())
                                .sortOrder(pq.getSortOrder())
                                .userAnswer(sub != null ? sub.getAnswer() : null)
                                .userScore(sub != null ? sub.getScore() : null)
                                .isCorrect(sub != null ? isCorrect : null)
                                .answered(sub != null)
                                .build());
            } else {
                QuestionTypeStrategy strategy = strategyFactory.getStrategy(pq.getQuestionType());
                if (strategy.isPractical()) {
                    strategy.buildPracticePracticalVO(
                                    pq.getQuestionId(),
                                    pq.getId(),
                                    pq.getScore(),
                                    pq.getSortOrder(),
                                    sub != null ? sub.getStatus() : "UNATTEMPTED",
                                    sub != null && sub.getScore() != null ? sub.getScore() : 0,
                                    sub != null ? sub.getSubmissionId() : null)
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
                .myJoined(myMember != null)
                .myScore(
                        myMember != null && myMember.getTotalScore() != null
                                ? myMember.getTotalScore()
                                : 0)
                .mySolvedCount(
                        myMember != null && myMember.getSolvedCount() != null
                                ? myMember.getSolvedCount()
                                : 0)
                .mySubmitted(myMember != null && Integer.valueOf(1).equals(myMember.getSubmitted()))
                .objectiveQuestions(objectiveList)
                .practicalQuestions(practicalList)
                .build();
    }

    /** 客观题集中答题并批量即时判分。 */
    @Transactional
    public PracticeObjectiveResultVO submitObjective(
            String accessCode, Long userId, PracticeObjectiveSubmitRequest req) {
        String code = accessCode.trim().toUpperCase();
        LessonPractice practice = practiceMapper.findByAccessCode(code);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        if ("ENDED".equals(practice.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "本次课练已结束，无法继续作答");
        }

        // 随堂练习定位：只要课练处于未截止状态，完全支持随时订正并重新判分
        LessonPracticeMember member = memberMapper.findByPracticeAndUser(practice.getId(), userId);
        if (member == null) {
            member =
                    LessonPracticeMember.builder()
                            .practiceId(practice.getId())
                            .userId(userId)
                            .totalScore(0)
                            .solvedCount(0)
                            .joinedAt(LocalDateTime.now())
                            .submitted(0)
                            .build();
            memberMapper.insert(member);
        }

        List<LessonPracticeQuestion> questions =
                questionMapper.listByPracticeId(practice.getId()).stream()
                        .filter(q -> "OBJECTIVE".equals(q.getQuestionType()))
                        .toList();

        List<PracticeObjectiveResultVO.ItemResult> itemResults = new ArrayList<>();
        int totalEarned = 0;
        int totalFull = 0;
        int correctCount = 0;

        for (LessonPracticeQuestion pq : questions) {
            totalFull += pq.getScore();
            String submittedAnswer = req.getAnswers().get(pq.getId());
            if (submittedAnswer == null) {
                submittedAnswer = "";
            }

            ObjectiveQuestion oq = objectiveQuestionMapper.findById(pq.getQuestionId());
            AnswerResultVO gradeResult =
                    oq != null
                            ? objectiveGrader.grade(oq, submittedAnswer)
                            : AnswerResultVO.builder()
                                    .correct(false)
                                    .score(0)
                                    .fullScore(pq.getScore())
                                    .build();

            // 折算满分权重
            int questionScore =
                    (int)
                            Math.round(
                                    (double) gradeResult.getScore()
                                            * pq.getScore()
                                            / (gradeResult.getFullScore() > 0
                                                    ? gradeResult.getFullScore()
                                                    : 1));
            if (gradeResult.getScore() > 0 && questionScore == 0 && pq.getScore() > 0) {
                questionScore = 1; // 漏选或部分对保底 1 分，绝不吞分
            }
            totalEarned += questionScore;
            if (gradeResult.isCorrect()) {
                correctCount++;
            }

            String status =
                    gradeResult.isCorrect()
                            ? "ACCEPTED"
                            : (questionScore > 0 ? "PARTIAL" : "WRONG_ANSWER");

            // 保存或更新提交
            LessonPracticeSubmission existSub =
                    submissionMapper.findByPracticeUserQuestion(
                            practice.getId(), userId, pq.getId());
            if (existSub != null) {
                existSub.setAnswer(submittedAnswer);
                existSub.setStatus(status);
                existSub.setScore(questionScore);
                submissionMapper.updateSubmission(existSub);
            } else {
                LessonPracticeSubmission newSub =
                        LessonPracticeSubmission.builder()
                                .practiceId(practice.getId())
                                .practiceQuestionId(pq.getId())
                                .userId(userId)
                                .questionType("OBJECTIVE")
                                .answer(submittedAnswer)
                                .status(status)
                                .score(questionScore)
                                .createdAt(LocalDateTime.now())
                                .build();
                submissionMapper.insert(newSub);
            }

            itemResults.add(
                    PracticeObjectiveResultVO.ItemResult.builder()
                            .practiceQuestionId(pq.getId())
                            .questionId(pq.getQuestionId())
                            .correct(gradeResult.isCorrect())
                            .score(questionScore)
                            .fullScore(pq.getScore())
                            .userAnswer(submittedAnswer)
                            .correctAnswer(oq != null ? oq.getAnswer() : null)
                            .build());
        }

        // 重新统计用户在本次课练中的总分与 AC 题目数
        recalculateMemberScore(practice.getId(), userId);

        return PracticeObjectiveResultVO.builder()
                .totalScore(totalEarned)
                .fullScore(totalFull)
                .correctCount(correctCount)
                .totalCount(questions.size())
                .items(itemResults)
                .build();
    }

    /** 将编程/数据库实战提交与课练上下文关联并即刻统计。 */
    @Transactional
    public void recordPracticalSubmission(
            String accessCode,
            Long userId,
            Long practiceQuestionId,
            Long submissionId,
            String questionType,
            String status,
            int score) {
        LessonPractice practice = practiceMapper.findByAccessCode(accessCode.trim().toUpperCase());
        if (practice == null) return;
        if ("ENDED".equals(practice.getStatus())) return;

        LessonPracticeMember member = memberMapper.findByPracticeAndUser(practice.getId(), userId);
        if (member != null && member.getSubmitted() == 1) return;

        LessonPracticeQuestion pq = questionMapper.findById(practiceQuestionId);
        if (pq == null || !practice.getId().equals(pq.getPracticeId())) {
            return;
        }
        int fullScore = pq.getScore() != null ? pq.getScore() : 10;

        // 根据判题记录校验真实得分与测试用例部分分，必须严格校验归属防止伪造
        JudgeInfo info = null;
        if (submissionId != null) {
            info =
                    verifyAndExtractJudgeInfo(
                            questionType, submissionId, userId, pq.getQuestionId());
        }

        int judgeRawScore = info != null && info.score() != null ? info.score() : 0;
        String judgeStatus =
                info != null && info.status() != null
                        ? info.status()
                        : (status != null && "PENDING".equalsIgnoreCase(status)
                                ? "PENDING"
                                : "UNATTEMPTED");

        int calculatedScore = calculateEarnedScore(judgeRawScore, fullScore, judgeStatus);
        String finalStatus = resolvePracticeStatus(judgeStatus, calculatedScore, fullScore);

        LessonPracticeSubmission exist =
                submissionMapper.findByPracticeUserQuestion(
                        practice.getId(), userId, practiceQuestionId);
        if (exist != null) {
            // 保留历史最高得分与最优状态，避免后续错误提交覆盖已有得分
            int currentScore = exist.getScore() != null ? exist.getScore() : 0;
            int bestScore = Math.max(currentScore, calculatedScore);
            String bestStatus =
                    "ACCEPTED".equals(exist.getStatus()) || "ACCEPTED".equals(finalStatus)
                            ? "ACCEPTED"
                            : (bestScore > 0 ? "PARTIAL" : finalStatus);
            exist.setSubmissionId(submissionId != null ? submissionId : exist.getSubmissionId());
            exist.setStatus(bestStatus);
            exist.setScore(bestScore);
            submissionMapper.updateSubmission(exist);
        } else {
            LessonPracticeSubmission sub =
                    LessonPracticeSubmission.builder()
                            .practiceId(practice.getId())
                            .practiceQuestionId(practiceQuestionId)
                            .userId(userId)
                            .questionType(questionType)
                            .submissionId(submissionId)
                            .status(finalStatus)
                            .score(calculatedScore)
                            .createdAt(LocalDateTime.now())
                            .build();
            submissionMapper.insert(sub);
        }

        recalculateMemberScore(practice.getId(), userId);
    }

    /** 重新计算成员总分与通过题目数（按题目去重取最高得分，确保部分分百分百计入）。 */
    private void recalculateMemberScore(Long practiceId, Long userId) {
        List<LessonPracticeSubmission> subs =
                submissionMapper.listByPracticeAndUser(practiceId, userId);

        // 1. 自动校正并补全历史未计入的部分分
        for (LessonPracticeSubmission s : subs) {
            if (!"OBJECTIVE".equals(s.getQuestionType()) && s.getSubmissionId() != null) {
                LessonPracticeQuestion pq = questionMapper.findById(s.getPracticeQuestionId());
                int fullScore = pq != null && pq.getScore() != null ? pq.getScore() : 10;
                JudgeInfo info = extractJudgeInfo(s.getQuestionType(), s.getSubmissionId());
                if (info != null && !isJudging(info.status())) {
                    int earned = calculateEarnedScore(info.score(), fullScore, info.status());
                    String status = resolvePracticeStatus(info.status(), earned, fullScore);
                    int currentScore = s.getScore() != null ? s.getScore() : 0;
                    if (earned > currentScore
                            || (!"ACCEPTED".equals(s.getStatus()) && "ACCEPTED".equals(status))) {
                        s.setScore(Math.max(currentScore, earned));
                        s.setStatus("ACCEPTED".equals(s.getStatus()) ? "ACCEPTED" : status);
                        submissionMapper.updateSubmission(s);
                    }
                }
            }
        }

        // 2. 按题目聚合并取最优得分与状态（防止多条提交记录重复计算或部分分被低分覆盖）
        Map<Long, LessonPracticeSubmission> bestMap = new HashMap<>();
        for (LessonPracticeSubmission s : subs) {
            if (s.getPracticeQuestionId() == null) continue;
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
                                .filter(
                                        s ->
                                                !"OBJECTIVE".equals(s.getQuestionType())
                                                        && "ACCEPTED".equals(s.getStatus()))
                                .count();

        memberMapper.updateScoreAndSolved(practiceId, userId, totalScore, solvedCount);
    }

    private record JudgeInfo(String status, Integer score) {}

    /** 校验提交记录是否真实归属于当前用户以及对应的题目，防止伪造其他学生的 submissionId。 */
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

    /** 提取评测结果：优先 Redis 实时状态缓存，缺失时回退 MySQL 及 resultJson 解析。 */
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
            earned = 1; // 只要测试点有得分，至少得 1 分，绝不吞分
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

    /** 查找用户在指定题目上的最新提交（防客户端轮询中断导致未计分）。 */
    private Long findLatestUserSubmission(Long userId, LessonPracticeQuestion pq) {
        if (userId == null || pq == null || pq.getQuestionId() == null) return null;
        return strategyFactory
                .findStrategy(pq.getQuestionType())
                .map(s -> s.findLatestUserSubmissionId(userId, pq.getQuestionId()))
                .orElse(null);
    }

    /** 随堂课练成员作答自愈对齐与重新统计。 */
    private void syncAndRecalculateMember(
            LessonPractice practice, Long userId, List<LessonPracticeQuestion> questions) {
        if (userId == null || practice == null) return;
        List<LessonPracticeSubmission> subs =
                submissionMapper.listByPracticeAndUser(practice.getId(), userId);
        Map<Long, LessonPracticeSubmission> subByPqId =
                subs.stream()
                        .filter(s -> s.getPracticeQuestionId() != null)
                        .collect(
                                Collectors.toMap(
                                        LessonPracticeSubmission::getPracticeQuestionId,
                                        s -> s,
                                        (a, b) -> a.getId() >= b.getId() ? a : b));

        for (LessonPracticeQuestion pq : questions) {
            if ("OBJECTIVE".equals(pq.getQuestionType())) continue;
            int fullScore = pq.getScore() != null ? pq.getScore() : 10;
            LessonPracticeSubmission exist = subByPqId.get(pq.getId());

            Long subId = exist != null ? exist.getSubmissionId() : null;

            // 若没有关联提交或当前分数为 0 且未 AC，尝试自动找回用户在该题上的最新提交
            if (subId == null
                    || ((exist.getScore() == null || exist.getScore() == 0)
                            && !"ACCEPTED".equals(exist.getStatus()))) {
                Long foundSubId = findLatestUserSubmission(userId, pq);
                if (foundSubId != null) {
                    subId = foundSubId;
                }
            }

            if (subId != null) {
                JudgeInfo info = extractJudgeInfo(pq.getQuestionType(), subId);
                if (info != null && !isJudging(info.status())) {
                    int earned = calculateEarnedScore(info.score(), fullScore, info.status());
                    String status = resolvePracticeStatus(info.status(), earned, fullScore);

                    if (exist != null) {
                        int currentScore = exist.getScore() != null ? exist.getScore() : 0;
                        int bestScore = Math.max(currentScore, earned);
                        String bestStatus =
                                "ACCEPTED".equals(exist.getStatus()) || "ACCEPTED".equals(status)
                                        ? "ACCEPTED"
                                        : (bestScore > 0 ? "PARTIAL" : status);
                        if (bestScore != currentScore
                                || !bestStatus.equals(exist.getStatus())
                                || !Objects.equals(subId, exist.getSubmissionId())) {
                            exist.setSubmissionId(subId);
                            exist.setScore(bestScore);
                            exist.setStatus(bestStatus);
                            submissionMapper.updateSubmission(exist);
                        }
                    } else {
                        exist =
                                LessonPracticeSubmission.builder()
                                        .practiceId(practice.getId())
                                        .practiceQuestionId(pq.getId())
                                        .userId(userId)
                                        .questionType(pq.getQuestionType())
                                        .submissionId(subId)
                                        .status(status)
                                        .score(earned)
                                        .createdAt(LocalDateTime.now())
                                        .build();
                        submissionMapper.insert(exist);
                        subByPqId.put(pq.getId(), exist);
                    }
                }
            }
        }

        recalculateMemberScore(practice.getId(), userId);
    }

    /** 学生主动交卷。 */
    @Transactional
    public void submitPractice(String accessCode, Long userId) {
        LessonPractice practice = practiceMapper.findByAccessCode(accessCode.trim().toUpperCase());
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        memberMapper.markSubmitted(practice.getId(), userId, LocalDateTime.now());
    }

    /** 获取教师端课堂投屏大屏实时数据。 */
    public PracticeMonitorVO getMonitorData(String accessCode, Long teacherId) {
        String code = accessCode.trim().toUpperCase();
        LessonPractice practice = practiceMapper.findByAccessCode(code);
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        checkPracticeManagePermission(practice, teacherId);

        int joinedCount = memberMapper.countByPracticeId(practice.getId());
        int submittedCount = memberMapper.countSubmittedByPracticeId(practice.getId());
        int totalSubmissions = submissionMapper.countTotalByPracticeId(practice.getId());

        List<LessonPracticeMember> members = memberMapper.listByPracticeId(practice.getId());
        double avgScore =
                members.isEmpty()
                        ? 0.0
                        : members.stream()
                                .mapToInt(LessonPracticeMember::getTotalScore)
                                .average()
                                .orElse(0.0);

        List<LessonPracticeQuestion> questions = questionMapper.listByPracticeId(practice.getId());
        int totalPossibleScore =
                questions.stream().mapToInt(LessonPracticeQuestion::getScore).sum();
        int perfectCount =
                (int)
                        members.stream()
                                .filter(
                                        m ->
                                                m.getTotalScore() >= totalPossibleScore
                                                        && totalPossibleScore > 0)
                                .count();

        LocalDateTime now = LocalDateTime.now();
        Long remainingSeconds = null;
        if ("ENDED".equals(practice.getStatus())) {
            remainingSeconds = 0L;
        } else if (practice.getEndTime() != null) {
            remainingSeconds =
                    Math.max(0, Duration.between(now, practice.getEndTime()).getSeconds());
            if (remainingSeconds <= 0 && "ACTIVE".equals(practice.getStatus())) {
                practice.setStatus("ENDED");
                practiceMapper.updateStatusAndEndTime(practice.getId(), "ENDED", now);
                remainingSeconds = 0L;
            }
        }

        // 逐题统计
        List<PracticeMonitorVO.QuestionStatItem> questionStats = new ArrayList<>();
        for (LessonPracticeQuestion q : questions) {
            int submitCount = submissionMapper.countSubmitByPracticeQuestionId(q.getId());
            int passCount = submissionMapper.countPassByPracticeQuestionId(q.getId());
            double rate = submitCount > 0 ? (double) passCount / submitCount * 100 : 0.0;

            questionStats.add(
                    PracticeMonitorVO.QuestionStatItem.builder()
                            .practiceQuestionId(q.getId())
                            .questionId(q.getQuestionId())
                            .questionType(q.getQuestionType())
                            .title(q.getTitle())
                            .score(q.getScore())
                            .submitCount(submitCount)
                            .passCount(passCount)
                            .passRate(Math.round(rate * 10.0) / 10.0)
                            .build());
        }

        // 实时提交流水
        List<LessonPracticeSubmission> recentSubs =
                submissionMapper.listRecentByPracticeId(practice.getId(), 15);
        Map<Long, String> qTitleMap =
                questions.stream()
                        .collect(
                                Collectors.toMap(
                                        LessonPracticeQuestion::getId,
                                        LessonPracticeQuestion::getTitle,
                                        (a, b) -> a));

        Set<Long> monitorUserIds = new HashSet<>();
        for (LessonPracticeSubmission s : recentSubs) {
            if (s.getUserId() != null) monitorUserIds.add(s.getUserId());
        }
        for (LessonPracticeMember m : members) {
            if (m.getUserId() != null) monitorUserIds.add(m.getUserId());
        }
        Map<Long, User> userMap =
                monitorUserIds.isEmpty()
                        ? Map.of()
                        : userMapper.findByIds(monitorUserIds).stream()
                                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<PracticeMonitorVO.RecentActivityItem> recentActivities = new ArrayList<>();
        for (LessonPracticeSubmission s : recentSubs) {
            User u = userMap.get(s.getUserId());
            recentActivities.add(
                    PracticeMonitorVO.RecentActivityItem.builder()
                            .id(s.getId())
                            .username(u != null ? u.getUsername() : "学生")
                            .nickname(u != null ? u.getUsername() : "学生")
                            .questionTitle(qTitleMap.getOrDefault(s.getPracticeQuestionId(), "题目"))
                            .questionType(s.getQuestionType())
                            .status(s.getStatus())
                            .score(s.getScore())
                            .createdAt(s.getCreatedAt())
                            .build());
        }

        // 天梯排行榜
        List<PracticeMonitorVO.LeaderboardItem> leaderboard = new ArrayList<>();
        int rank = 1;
        for (LessonPracticeMember m : members) {
            User u = userMap.get(m.getUserId());
            leaderboard.add(
                    PracticeMonitorVO.LeaderboardItem.builder()
                            .rank(rank++)
                            .userId(m.getUserId())
                            .username(u != null ? u.getUsername() : "学生" + m.getUserId())
                            .nickname(u != null ? u.getUsername() : "学生" + m.getUserId())
                            .solvedCount(m.getSolvedCount())
                            .totalScore(m.getTotalScore())
                            .submitted(m.getSubmitted() == 1)
                            .joinedAt(m.getJoinedAt())
                            .build());
        }

        return PracticeMonitorVO.builder()
                .practiceId(practice.getId())
                .title(practice.getTitle())
                .accessCode(practice.getAccessCode())
                .durationMinutes(practice.getDurationMinutes())
                .status(practice.getStatus())
                .remainingSeconds(remainingSeconds)
                .joinedCount(joinedCount)
                .submittedCount(submittedCount)
                .totalSubmissions(totalSubmissions)
                .avgScore(Math.round(avgScore * 10.0) / 10.0)
                .perfectScoreCount(perfectCount)
                .questionStats(questionStats)
                .recentActivities(recentActivities)
                .leaderboard(leaderboard)
                .build();
    }

    private void checkPracticeManagePermission(LessonPractice practice, Long teacherId) {
        if (teacherId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        User user = userMapper.findById(teacherId);
        boolean isAdmin =
                user != null && user.getRoles() != null && user.getRoles().contains("ADMIN");
        if (!isAdmin && !practice.getTeacherId().equals(teacherId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权管理该课练");
        }
    }

    /** 教师提前结束课练。 */
    @Transactional
    public void endPractice(String accessCode, Long teacherId) {
        LessonPractice practice = practiceMapper.findByAccessCode(accessCode.trim().toUpperCase());
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        checkPracticeManagePermission(practice, teacherId);
        practiceMapper.updateStatusAndEndTime(practice.getId(), "ENDED", LocalDateTime.now());
    }

    /** 教师延长课练时长。 */
    @Transactional
    public void extendDuration(String accessCode, int extraMinutes, Long teacherId) {
        LessonPractice practice = practiceMapper.findByAccessCode(accessCode.trim().toUpperCase());
        if (practice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课练不存在");
        }
        checkPracticeManagePermission(practice, teacherId);
        int newDuration = practice.getDurationMinutes() + extraMinutes;
        LocalDateTime newEndTime =
                practice.getEndTime() != null
                        ? practice.getEndTime().plusMinutes(extraMinutes)
                        : LocalDateTime.now().plusMinutes(newDuration);
        practiceMapper.updateDuration(practice.getId(), newDuration, newEndTime);
    }

    /** 教师或管理员删除课练（级联清理题目、学生作答与成员记录）。教师仅可删自己的，管理员可删全部。 */
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

    /** 分页查询课练列表（支持关键词、状态及指定教师筛选）。 */
    public Map<String, Object> listPractices(
            String keyword, String status, Long teacherId, Long currentUserId, int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        String kw = keyword != null && !keyword.isBlank() ? keyword.trim() : null;
        String st = status != null && !status.isBlank() ? status.trim() : null;
        List<LessonPractice> list = practiceMapper.listFilter(kw, st, teacherId, offset, size);
        int total = practiceMapper.countFilter(kw, st, teacherId);

        List<PracticeSummaryVO> voList =
                list.stream().map(p -> getSummaryVO(p, currentUserId)).toList();

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

        boolean canSeeCode = false;
        if (currentUserId != null) {
            User currentUser = userMapper.findById(currentUserId);
            boolean isAdmin =
                    currentUser != null
                            && currentUser.getRoles() != null
                            && currentUser.getRoles().contains("ADMIN");
            boolean isTeacherOfThis = Objects.equals(p.getTeacherId(), currentUserId);
            boolean isJoined = member != null;
            canSeeCode = isAdmin || isTeacherOfThis || isJoined;
        }

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
