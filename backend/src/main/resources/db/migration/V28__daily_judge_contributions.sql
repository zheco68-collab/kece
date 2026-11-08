CREATE TABLE user_daily_judge_contribution (
    submission_type VARCHAR(16) NOT NULL,
    submission_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    ac_date DATE NOT NULL,
    PRIMARY KEY (submission_type, submission_id),
    KEY idx_judge_contribution_date (user_id, ac_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;

INSERT INTO user_daily_judge_contribution (submission_type, submission_id, user_id, ac_date)
SELECT 'ALGO', id, user_id, DATE(created_at)
FROM submission WHERE status = 'ACCEPTED'
UNION ALL
SELECT 'DATABASE', id, user_id, DATE(created_at)
FROM db_submission WHERE status = 'ACCEPTED';

UPDATE user_daily_ac
SET total_count = GREATEST(total_count - algo_count - db_count, 0), algo_count = 0, db_count = 0;

INSERT INTO user_daily_ac (user_id, ac_date, algo_count, db_count, total_count)
SELECT user_id, ac_date, SUM(submission_type = 'ALGO'), SUM(submission_type = 'DATABASE'), COUNT(*)
FROM user_daily_judge_contribution
GROUP BY user_id, ac_date
ON DUPLICATE KEY UPDATE algo_count = VALUES(algo_count), db_count = VALUES(db_count),
                        total_count = total_count + VALUES(total_count);

COMMIT;
