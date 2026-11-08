package com.kece.judging.control;

import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.practice.service.LessonPracticeService;
import com.kece.problemset.service.ProblemSetService;
import com.kece.submission.mapper.SubmissionMapper;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JudgeSettlementEvents {
    private final JdbcTemplate db;
    private final SubmissionMapper submissions;
    private final DbSubmissionMapper sqlSubmissions;
    private final LessonPracticeService practice;
    private final ProblemSetService problemSets;

    private record Event(long job, String type, long submission) {}

    public List<Long> due() {
        return db.query(
                """
                SELECT id FROM judge_settlement_event WHERE state='PENDING' AND available_at<=UTC_TIMESTAMP(6)
                ORDER BY available_at,id LIMIT 16
                """,
                (r, i) -> r.getLong(1));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void apply(long id) {
        Event e =
                db
                        .query(
                                """
                SELECT j.id,j.submission_type,j.submission_id
                FROM judge_settlement_event e JOIN judge_job j ON j.id=e.job_id WHERE e.id=?
                """,
                                (r, i) -> new Event(r.getLong(1), r.getString(2), r.getLong(3)),
                                id)
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (e == null) {
            return;
        }
        boolean code = "ALGO".equals(e.type());
        var sub = code ? submissions.findByIdForUpdate(e.submission()) : null;
        var sql = code ? null : sqlSubmissions.findByIdForUpdate(e.submission());
        Long current =
                sub != null
                        ? sub.getCurrentJudgeJobId()
                        : sql != null ? sql.getCurrentJudgeJobId() : null;
        boolean pending =
                !db.query(
                                """
                SELECT id FROM judge_settlement_event WHERE id=? AND state='PENDING'
                   AND available_at<=UTC_TIMESTAMP(6) FOR UPDATE
                """,
                                (r, i) -> r.getLong(1),
                                id)
                        .isEmpty();
        if (!pending) {
            return;
        }
        if (Objects.equals(current, e.job())) {
            String type = code ? "ALGO" : "DB";
            practice.onPracticalJudgeCompleted(type, e.submission());
            Long userId = sub != null ? sub.getUserId() : sql.getUserId();
            Long questionId = sub != null ? sub.getQuestionId() : sql.getQuestionId();
            problemSets.reconcileJudgeProgress(userId, type, questionId);
        }
        db.update(
                """
                UPDATE judge_settlement_event SET state='DONE',processed_at=UTC_TIMESTAMP(6),last_error=NULL
                WHERE id=?
                """,
                id);
    }

    public void retry(long id, String error) {
        db.update(
                """
                UPDATE judge_settlement_event
                SET available_at=TIMESTAMPADD(SECOND,LEAST(300, POW(2, LEAST(attempt_count+1,9))),UTC_TIMESTAMP(6)),
                  attempt_count=attempt_count+1,last_error=?
                WHERE id=? AND state='PENDING' AND available_at<=UTC_TIMESTAMP(6)
                """,
                error,
                id);
    }
}
