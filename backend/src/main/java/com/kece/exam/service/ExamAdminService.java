package com.kece.exam.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.database.entity.DbQuestion;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.exam.dto.ExamAdminOverviewVO;
import com.kece.exam.dto.ExamQuestionVO;
import com.kece.exam.dto.ExamStudentInfoVO;
import com.kece.exam.dto.ExamStudentPaperQuestionVO;
import com.kece.exam.dto.ExamStudentPaperVO;
import com.kece.exam.dto.ExamStudentSubmissionRowVO;
import com.kece.exam.dto.ExamVO;
import com.kece.exam.entity.Exam;
import com.kece.exam.entity.ExamQuestion;
import com.kece.exam.entity.ExamStudentInfo;
import com.kece.exam.entity.ExamSubmission;
import com.kece.exam.mapper.ExamMapper;
import com.kece.exam.mapper.ExamQuestionMapper;
import com.kece.exam.mapper.ExamStudentInfoMapper;
import com.kece.exam.mapper.ExamSubmissionMapper;
import com.kece.objective.entity.ObjectiveQuestion;
import com.kece.objective.mapper.ObjectiveQuestionMapper;
import com.kece.question.entity.Question;
import com.kece.question.mapper.QuestionMapper;
import com.kece.question.strategy.QuestionStrategyFactory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 教师后台考试查卷、学生作答与成绩总览业务服务。 */
@Service
@RequiredArgsConstructor
public class ExamAdminService {

    private final ExamMapper examMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamSubmissionMapper submissionMapper;
    private final ExamStudentInfoMapper studentInfoMapper;
    private final QuestionMapper questionMapper;
    private final DbQuestionMapper dbQuestionMapper;
    private final ObjectiveQuestionMapper objectiveQuestionMapper;
    private final com.kece.user.mapper.UserMapper userMapper;
    private final QuestionStrategyFactory strategyFactory;
    private final ObjectMapper objectMapper;

    /** 教师查看某场考试的全场考生试卷与成绩总览。 */
    public ExamAdminOverviewVO getAdminOverview(Long examId) {
        Exam exam = getExamOrThrow(examId);
        checkOwnerOrAdmin(exam);

        List<ExamQuestion> examQuestions = examQuestionMapper.findByExamId(examId);
        int examTotalFullScore =
                examQuestions.stream().mapToInt(q -> q.getScore() == null ? 0 : q.getScore()).sum();

        List<ExamSubmission> allSubmissions = submissionMapper.findByExam(examId);
        List<ExamStudentInfo> allStudentInfos = studentInfoMapper.findByExamId(examId);

        Map<Long, ExamStudentInfo> studentInfoMap =
                allStudentInfos.stream()
                        .collect(
                                Collectors.toMap(
                                        ExamStudentInfo::getUserId, si -> si, (a, b) -> a));

        Map<Long, List<ExamSubmission>> subsByUser =
                allSubmissions.stream().collect(Collectors.groupingBy(ExamSubmission::getUserId));

        Set<Long> userIds = new HashSet<>();
        userIds.addAll(subsByUser.keySet());
        userIds.addAll(studentInfoMap.keySet());
        Map<Long, com.kece.user.entity.User> userMap =
                userIds.isEmpty()
                        ? Map.of()
                        : userMapper.findByIds(userIds).stream()
                                .collect(
                                        Collectors.toMap(
                                                com.kece.user.entity.User::getId,
                                                u -> u,
                                                (a, b) -> a));

        List<ExamStudentSubmissionRowVO> students = new ArrayList<>();
        for (Long uid : userIds) {
            com.kece.user.entity.User user = userMap.get(uid);
            String username = user != null ? user.getUsername() : ("UID:" + uid);
            ExamStudentInfo info = studentInfoMap.get(uid);
            List<ExamSubmission> userSubs = subsByUser.getOrDefault(uid, List.of());
            Map<Long, ExamSubmission> subByQuestionId =
                    userSubs.stream()
                            .collect(
                                    Collectors.toMap(
                                            ExamSubmission::getExamQuestionId,
                                            s -> s,
                                            (a, b) -> a));

            boolean submitted =
                    userSubs.stream()
                            .anyMatch(s -> s.getSubmitted() != null && s.getSubmitted() == 1);
            LocalDateTime submittedAt =
                    userSubs.stream()
                            .filter(s -> s.getSubmitted() != null && s.getSubmitted() == 1)
                            .map(ExamSubmission::getSubmittedAt)
                            .max(Comparator.naturalOrder())
                            .orElse(null);

            Map<Long, Integer> questionScores = new HashMap<>();
            Map<Long, String> questionStatuses = new HashMap<>();
            int totalScore = 0;
            int solvedCount = 0;

            for (ExamQuestion eq : examQuestions) {
                ExamSubmission sub = subByQuestionId.get(eq.getId());
                String status = sub != null ? sub.getStatus() : null;
                Integer score = sub != null ? sub.getScore() : null;

                if (strategyFactory != null) {
                    var latest =
                            strategyFactory
                                    .findStrategy(eq.getQuestionType())
                                    .flatMap(
                                            s ->
                                                    s.findLatestContestSubmission(
                                                            examId, uid, eq.getQuestionId()));
                    if (latest.isPresent()) {
                        status = latest.get().status();
                        score = scaleScore(latest.get().score(), eq.getScore(), status);
                    } else if (score != null && !"OBJECTIVE".equals(eq.getQuestionType())) {
                        score = scaleScore(score, eq.getScore(), status);
                    }
                }

                if ("ACCEPTED".equals(status)) {
                    solvedCount++;
                }
                if (score != null) {
                    totalScore += score;
                }
                questionScores.put(eq.getId(), score == null ? 0 : score);
                questionStatuses.put(eq.getId(), status == null ? "UNANSWERED" : status);
            }

            students.add(
                    ExamStudentSubmissionRowVO.builder()
                            .userId(uid)
                            .username(username)
                            .realName(info != null ? info.getRealName() : username)
                            .studentNo(info != null ? info.getStudentNo() : "—")
                            .college(info != null ? info.getCollege() : "—")
                            .major(info != null ? info.getMajor() : "—")
                            .className(info != null ? info.getClassName() : "—")
                            .totalScore(totalScore)
                            .fullScore(examTotalFullScore)
                            .solvedCount(solvedCount)
                            .totalQuestions(examQuestions.size())
                            .submitted(submitted)
                            .submittedAt(submittedAt)
                            .questionScores(questionScores)
                            .questionStatuses(questionStatuses)
                            .build());
        }

        // 默认按总分降序排列
        students.sort(Comparator.comparing(ExamStudentSubmissionRowVO::getTotalScore).reversed());

        int totalStudents = students.size();
        int submittedCount =
                (int) students.stream().filter(ExamStudentSubmissionRowVO::getSubmitted).count();
        double avgScore =
                totalStudents > 0
                        ? students.stream()
                                .mapToInt(ExamStudentSubmissionRowVO::getTotalScore)
                                .average()
                                .orElse(0.0)
                        : 0.0;
        int maxScore =
                students.stream()
                        .mapToInt(ExamStudentSubmissionRowVO::getTotalScore)
                        .max()
                        .orElse(0);
        int minScore =
                students.stream()
                        .mapToInt(ExamStudentSubmissionRowVO::getTotalScore)
                        .min()
                        .orElse(0);

        return ExamAdminOverviewVO.builder()
                .exam(toVO(exam, examQuestions))
                .totalStudents(totalStudents)
                .submittedCount(submittedCount)
                .avgScore(Math.round(avgScore * 10.0) / 10.0)
                .maxScore(maxScore)
                .minScore(minScore)
                .students(students)
                .build();
    }

    /** 教师查看指定学生的完整作答试卷明细（userId 为 null 时返回空白标准 A 卷模板）。 */
    public ExamStudentPaperVO getStudentPaper(Long examId, Long userId) {
        Exam exam = getExamOrThrow(examId);
        checkOwnerOrAdmin(exam);

        List<ExamQuestion> examQuestions = examQuestionMapper.findByExamId(examId);
        int examTotalFullScore =
                examQuestions.stream().mapToInt(q -> q.getScore() == null ? 0 : q.getScore()).sum();

        ExamStudentInfo studentInfo = null;
        String username = null;
        Map<Long, ExamSubmission> subByQuestionId = new HashMap<>();
        boolean submitted = false;
        LocalDateTime submittedAt = null;

        if (userId != null) {
            com.kece.user.entity.User user = userMapper.findById(userId);
            username = user != null ? user.getUsername() : ("UID:" + userId);
            studentInfo = studentInfoMapper.findByExamAndUser(examId, userId);
            List<ExamSubmission> userSubs = submissionMapper.findByExamAndUser(examId, userId);
            for (ExamSubmission s : userSubs) {
                subByQuestionId.put(s.getExamQuestionId(), s);
                if (s.getSubmitted() != null && s.getSubmitted() == 1) {
                    submitted = true;
                    if (submittedAt == null
                            || (s.getSubmittedAt() != null
                                    && s.getSubmittedAt().isAfter(submittedAt))) {
                        submittedAt = s.getSubmittedAt();
                    }
                }
            }
        }

        List<ExamStudentPaperQuestionVO> questionVOs = new ArrayList<>();
        int totalScore = 0;
        int solvedCount = 0;

        for (ExamQuestion eq : examQuestions) {
            String type = eq.getQuestionType();
            String title = eq.getTitle();
            int fullScore = eq.getScore() != null ? eq.getScore() : 5;
            String content = "";
            List<String> options = List.of();
            String objectiveType = null;
            String standardAnswer = "";

            if ("OBJECTIVE".equals(type)) {
                ObjectiveQuestion oq = objectiveQuestionMapper.findById(eq.getQuestionId());
                if (oq != null) {
                    content = oq.getContent();
                    if (content != null && !content.isBlank()) {
                        title = content;
                    }
                    options = parseOptions(oq.getOptions());
                    objectiveType = oq.getType();
                    standardAnswer = oq.getAnswer();
                }
            } else if ("ALGO".equals(type)) {
                Question q = questionMapper.findById(eq.getQuestionId());
                if (q != null) {
                    content = q.getDescription();
                    standardAnswer = "（编程算法实操题，采用隔离沙箱与自动化测试集评测）";
                }
            } else if ("DB".equals(type) || "DATABASE".equals(type)) {
                DbQuestion dbq = dbQuestionMapper.findById(eq.getQuestionId());
                if (dbq != null) {
                    content = dbq.getDescription();
                    standardAnswer = dbq.getReferenceSql();
                }
            }

            String studentAnswer = null;
            String language = null;
            String status = "UNANSWERED";
            Integer studentScore = userId != null ? 0 : null;
            Boolean isCorrect = null;
            String resultJson = null;

            if (userId != null) {
                ExamSubmission sub = subByQuestionId.get(eq.getId());
                if (sub != null) {
                    studentAnswer = sub.getAnswerContent();
                    language = sub.getLanguage();
                    status = sub.getStatus();
                    studentScore = sub.getScore();
                    resultJson = sub.getResultJson();

                    if (strategyFactory != null) {
                        var latest =
                                strategyFactory
                                        .findStrategy(type)
                                        .flatMap(
                                                s ->
                                                        s.findLatestContestSubmission(
                                                                examId,
                                                                userId,
                                                                eq.getQuestionId()));
                        if (latest.isPresent()) {
                            status = latest.get().status();
                            studentScore = scaleScore(latest.get().score(), fullScore, status);
                        } else if (studentScore != null && !"OBJECTIVE".equals(type)) {
                            studentScore = scaleScore(studentScore, fullScore, status);
                        }
                    }

                    isCorrect =
                            "ACCEPTED".equals(status)
                                    || (studentScore != null && studentScore.equals(fullScore));
                } else {
                    isCorrect = false;
                }

                if (studentScore != null) {
                    totalScore += studentScore;
                }
                if ("ACCEPTED".equals(status)) {
                    solvedCount++;
                }
            }

            questionVOs.add(
                    ExamStudentPaperQuestionVO.builder()
                            .examQuestionId(eq.getId())
                            .questionId(eq.getQuestionId())
                            .questionType(type)
                            .objectiveType(objectiveType)
                            .title(title)
                            .content(content)
                            .options(options)
                            .fullScore(fullScore)
                            .studentScore(studentScore)
                            .status(status)
                            .studentAnswer(studentAnswer)
                            .standardAnswer(standardAnswer)
                            .isCorrect(isCorrect)
                            .language(language)
                            .resultJson(resultJson)
                            .build());
        }

        ExamStudentInfoVO infoVO = null;
        if (studentInfo != null) {
            infoVO =
                    ExamStudentInfoVO.builder()
                            .id(studentInfo.getId())
                            .examId(examId)
                            .userId(userId)
                            .realName(studentInfo.getRealName())
                            .studentNo(studentInfo.getStudentNo())
                            .college(studentInfo.getCollege())
                            .major(studentInfo.getMajor())
                            .className(studentInfo.getClassName())
                            .build();
        } else if (userId != null) {
            infoVO =
                    ExamStudentInfoVO.builder()
                            .examId(examId)
                            .userId(userId)
                            .realName(username)
                            .studentNo("—")
                            .college("—")
                            .major("—")
                            .className("—")
                            .build();
        }

        return ExamStudentPaperVO.builder()
                .exam(toVO(exam, examQuestions))
                .studentInfo(infoVO)
                .userId(userId)
                .username(username)
                .totalScore(userId != null ? totalScore : null)
                .fullScore(examTotalFullScore)
                .solvedCount(userId != null ? solvedCount : null)
                .submitted(submitted)
                .submittedAt(submittedAt)
                .questions(questionVOs)
                .build();
    }

    private Integer scaleScore(Integer judgeScore, int fullScore, String status) {
        if ("ACCEPTED".equals(status)) {
            return fullScore;
        }
        if (judgeScore == null || judgeScore <= 0 || fullScore <= 0) {
            return 0;
        }
        int earned = (int) Math.round((double) judgeScore * fullScore / 100.0);
        if (earned == 0 && fullScore > 0 && judgeScore > 0) {
            earned = 1;
        }
        return Math.min(earned, fullScore);
    }

    private Exam getExamOrThrow(Long id) {
        Exam exam = examMapper.findById(id);
        if (exam == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "考试不存在");
        }
        return exam;
    }

    private void checkOwnerOrAdmin(Exam exam) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (user.hasRole(Role.ADMIN)) {
            return;
        }
        if (!exam.getCreatedBy().equals(user.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅考试出题人或管理员可查看");
        }
    }

    private ExamVO toVO(Exam exam, List<ExamQuestion> questions) {
        List<ExamQuestionVO> qVOs =
                questions.stream()
                        .map(
                                q ->
                                        ExamQuestionVO.builder()
                                                .id(q.getId())
                                                .questionId(q.getQuestionId())
                                                .questionType(q.getQuestionType())
                                                .title(q.getTitle())
                                                .score(q.getScore())
                                                .sortOrder(q.getSortOrder())
                                                .build())
                        .toList();

        return ExamVO.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .description(exam.getDescription())
                .durationMinutes(exam.getDurationMinutes())
                .startTime(exam.getStartTime())
                .endTime(exam.getEndTime())
                .mode(exam.getMode())
                .status(exam.getStatus())
                .requireStudentInfo(exam.getRequireStudentInfo())
                .schoolName(exam.getSchoolName())
                .semester(exam.getSemester())
                .sessionStatus(exam.getSessionStatus())
                .createdBy(exam.getCreatedBy())
                .questions(qVOs)
                .createdAt(exam.getCreatedAt())
                .updatedAt(exam.getUpdatedAt())
                .build();
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
