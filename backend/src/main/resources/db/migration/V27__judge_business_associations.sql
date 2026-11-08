CREATE TABLE practice_submission_binding (
    question_type VARCHAR(16) NOT NULL,
    submission_id BIGINT NOT NULL,
    practice_submission_id BIGINT NOT NULL,
    PRIMARY KEY (question_type, submission_id),
    FOREIGN KEY (practice_submission_id) REFERENCES lesson_practice_submission(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO practice_submission_binding (question_type, submission_id, practice_submission_id)
SELECT question_type, submission_id, MIN(practice_submission_id)
FROM (
    SELECT IF(question_type = 'ALGO', 'ALGO', 'DB') AS question_type,
           submission_id, id AS practice_submission_id
    FROM lesson_practice_submission
    WHERE question_type IN ('ALGO', 'DB', 'DATABASE') AND submission_id IS NOT NULL
    UNION ALL
    SELECT IF(question_type = 'ALGO', 'ALGO', 'DB'),
           best_submission_id, id
    FROM lesson_practice_submission
    WHERE question_type IN ('ALGO', 'DB', 'DATABASE') AND best_submission_id IS NOT NULL
) candidates
GROUP BY question_type, submission_id
HAVING COUNT(DISTINCT practice_submission_id) = 1;

CREATE TABLE exam_candidate_guard (
    exam_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    submitted_at DATETIME(6) NULL,
    PRIMARY KEY (exam_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO exam_candidate_guard (exam_id, user_id, submitted_at)
SELECT exam_id, user_id, UTC_TIMESTAMP(6)
FROM exam_submission
GROUP BY exam_id, user_id
HAVING MIN(COALESCE(submitted, 0)) = 1;

CREATE TABLE exam_request_receipt (
    exam_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii NOT NULL,
    response_json JSON NOT NULL,
    PRIMARY KEY (exam_id, user_id, request_key),
    FOREIGN KEY (exam_id, user_id)
        REFERENCES exam_candidate_guard(exam_id, user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
