package com.kece.exam.service;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.database.entity.DbSubmission;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.exam.dto.ExamRankVO;
import com.kece.exam.entity.Exam;
import com.kece.exam.entity.ExamQuestion;
import com.kece.exam.entity.ExamSubmission;
import com.kece.exam.mapper.ExamMapper;
import com.kece.exam.mapper.ExamQuestionMapper;
import com.kece.exam.mapper.ExamSubmissionMapper;
import com.kece.submission.entity.Submission;
import com.kece.submission.mapper.SubmissionMapper;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 考试排名：ACM（AC 数 + 罚时）与 OI（总分）双模式。 */
@Service
@RequiredArgsConstructor
public class ExamRankService {

    private final ExamMapper examMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamSubmissionMapper submissionMapper;
    private final SubmissionMapper codeSubmissionMapper;
    private final DbSubmissionMapper dbSubmissionMapper;
    private final UserMapper userMapper;

    public List<ExamRankVO> ranking(Long examId) {
        Exam exam = examMapper.findById(examId);
        if (exam == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "考试不存在");
        }
        boolean acm = "ACM".equalsIgnoreCase(exam.getMode());

        List<ExamSubmission> answers = submissionMapper.findByExam(examId);
        List<Submission> codeSubs = codeSubmissionMapper.findByContest(examId);
        List<DbSubmission> dbSubs = dbSubmissionMapper.findByContest(examId);

        // 代码/数据库题最新提交索引：key = userId:questionId
        Map<String, Submission> latestCode = new HashMap<>();
        for (Submission s : codeSubs) {
            latestCode.put(key(s.getUserId(), s.getQuestionId()), s);
        }
        Map<String, DbSubmission> latestDb = new HashMap<>();
        for (DbSubmission s : dbSubs) {
            latestDb.put(key(s.getUserId(), s.getQuestionId()), s);
        }

        List<ExamQuestion> examQuestions = examQuestionMapper.findByExamId(examId);
        Map<Long, ExamQuestion> eqMap =
                examQuestions.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        ExamQuestion::getId, q -> q, (a, b) -> a));
        int examTotalFullScore =
                examQuestions.stream().mapToInt(q -> q.getScore() == null ? 0 : q.getScore()).sum();

        Map<Long, RankAgg> aggByUser = new LinkedHashMap<>();
        for (ExamSubmission row : answers) {
            ExamQuestion eq = eqMap.get(row.getExamQuestionId());
            if (eq == null) {
                continue;
            }
            RankAgg agg =
                    aggByUser.computeIfAbsent(
                            row.getUserId(),
                            k -> {
                                RankAgg a = new RankAgg();
                                a.fullScore = examTotalFullScore;
                                return a;
                            });
            agg.finished++;
            String status = row.getStatus();
            Integer score = row.getScore();
            if ("ALGO".equals(eq.getQuestionType())) {
                Submission s = latestCode.get(key(row.getUserId(), eq.getQuestionId()));
                if (s != null) {
                    status = s.getStatus();
                    score = scaleScore(s.getScore(), eq.getScore(), status);
                }
            } else if ("DB".equals(eq.getQuestionType())
                    || "DATABASE".equals(eq.getQuestionType())) {
                DbSubmission s = latestDb.get(key(row.getUserId(), eq.getQuestionId()));
                if (s != null) {
                    status = s.getStatus();
                    score = scaleScore(s.getScore(), eq.getScore(), status);
                }
            }
            if (isAccepted(status)) {
                agg.solved++;
            }
            agg.totalScore += score == null ? 0 : score;
        }

        // 标准 ACM 罚时统计：仅对最终通过的题目累计罚时；未通过题目不计罚时；首 AC 后的提交不计罚时
        if (acm) {
            record ContestAttempt(
                    String type,
                    Long userId,
                    Long questionId,
                    java.time.LocalDateTime createdAt,
                    String status) {}
            List<ContestAttempt> allAttempts = new ArrayList<>();
            for (Submission s : codeSubs) {
                allAttempts.add(
                        new ContestAttempt(
                                "ALGO",
                                s.getUserId(),
                                s.getQuestionId(),
                                s.getCreatedAt(),
                                s.getStatus()));
            }
            for (DbSubmission s : dbSubs) {
                allAttempts.add(
                        new ContestAttempt(
                                "DB",
                                s.getUserId(),
                                s.getQuestionId(),
                                s.getCreatedAt(),
                                s.getStatus()));
            }
            allAttempts.sort(
                    Comparator.comparing(
                            ContestAttempt::createdAt,
                            Comparator.nullsLast(Comparator.naturalOrder())));

            Map<String, List<ContestAttempt>> attemptsByProblem = new HashMap<>();
            for (ContestAttempt a : allAttempts) {
                attemptsByProblem
                        .computeIfAbsent(
                                typedKey(a.type(), a.userId(), a.questionId()),
                                k -> new ArrayList<>())
                        .add(a);
            }

            for (Map.Entry<Long, RankAgg> entry : aggByUser.entrySet()) {
                Long uid = entry.getKey();
                RankAgg agg = entry.getValue();
                long totalPenalty = 0;
                int solvedCount = 0;

                for (ExamQuestion eq : examQuestions) {
                    if ("OBJECTIVE".equals(eq.getQuestionType())) {
                        ExamSubmission sub =
                                answers.stream()
                                        .filter(
                                                a ->
                                                        a.getUserId().equals(uid)
                                                                && a.getExamQuestionId()
                                                                        .equals(eq.getId()))
                                        .findFirst()
                                        .orElse(null);
                        if (sub != null && isAccepted(sub.getStatus())) {
                            solvedCount++;
                            if (sub.getCreatedAt() != null && exam.getStartTime() != null) {
                                totalPenalty +=
                                        Math.max(
                                                0,
                                                Duration.between(
                                                                exam.getStartTime(),
                                                                sub.getCreatedAt())
                                                        .toMinutes());
                            }
                        }
                        continue;
                    }

                    String normType = "ALGO".equals(eq.getQuestionType()) ? "ALGO" : "DB";
                    List<ContestAttempt> attempts =
                            attemptsByProblem.get(typedKey(normType, uid, eq.getQuestionId()));
                    if (attempts == null || attempts.isEmpty()) {
                        continue;
                    }

                    int wrongCount = 0;
                    boolean ac = false;
                    long acMinutes = 0;
                    for (ContestAttempt att : attempts) {
                        if (isAccepted(att.status())) {
                            ac = true;
                            if (att.createdAt() != null && exam.getStartTime() != null) {
                                acMinutes =
                                        Math.max(
                                                0,
                                                Duration.between(
                                                                exam.getStartTime(),
                                                                att.createdAt())
                                                        .toMinutes());
                            }
                            break;
                        } else {
                            wrongCount++;
                        }
                    }

                    if (ac) {
                        solvedCount++;
                        totalPenalty += (acMinutes + (long) wrongCount * 20);
                    }
                }
                agg.solved = solvedCount;
                agg.penaltyMinutes = totalPenalty;
            }
        }

        Map<Long, User> userMap =
                aggByUser.isEmpty()
                        ? Map.of()
                        : userMapper.findByIds(aggByUser.keySet()).stream()
                                .collect(
                                        java.util.stream.Collectors.toMap(
                                                User::getId, u -> u, (a, b) -> a));

        List<ExamRankVO> result = new ArrayList<>();
        for (Map.Entry<Long, RankAgg> e : aggByUser.entrySet()) {
            User user = userMap.get(e.getKey());
            RankAgg agg = e.getValue();
            result.add(
                    ExamRankVO.builder()
                            .userId(e.getKey())
                            .username(user == null ? "#" + e.getKey() : user.getUsername())
                            .solved(agg.solved)
                            .penaltyMinutes(agg.penaltyMinutes)
                            .totalScore(agg.totalScore)
                            .finished(agg.finished)
                            .fullScore(agg.fullScore)
                            .build());
        }
        Comparator<ExamRankVO> comparator =
                acm
                        ? Comparator.comparingInt(ExamRankVO::getSolved)
                                .reversed()
                                .thenComparing(ExamRankVO::getPenaltyMinutes)
                                .thenComparing(ExamRankVO::getUsername)
                        : Comparator.comparingInt(ExamRankVO::getTotalScore)
                                .reversed()
                                .thenComparing(ExamRankVO::getFinished, Comparator.reverseOrder())
                                .thenComparing(ExamRankVO::getUsername);
        result.sort(comparator);
        return result;
    }

    private boolean isAccepted(String status) {
        return "ACCEPTED".equals(status);
    }

    private Integer scaleScore(Integer judgeScore, Integer fullScore, String status) {
        if ("ACCEPTED".equals(status)) {
            return fullScore != null ? fullScore : 0;
        }
        if (judgeScore == null || fullScore == null || judgeScore <= 0 || fullScore <= 0) {
            return 0;
        }
        int earned = (int) Math.round((double) judgeScore * fullScore / 100.0);
        if (earned == 0 && fullScore > 0 && judgeScore > 0) {
            earned = 1;
        }
        return Math.min(earned, fullScore);
    }

    private String key(Long userId, Long questionId) {
        return userId + ":" + questionId;
    }

    private String typedKey(String type, Long userId, Long questionId) {
        return type + ":" + userId + ":" + questionId;
    }

    /** 排名聚合中间态。 */
    private static class RankAgg {
        private int solved;
        private long penaltyMinutes;
        private int totalScore;
        private int finished;
        private int fullScore;
    }
}
