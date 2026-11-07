-- 后台概况按时间范围汇总编程题和数据库题提交。
ALTER TABLE submission ADD INDEX idx_submission_created_at (created_at);
ALTER TABLE db_submission ADD INDEX idx_db_submission_created_at (created_at);
