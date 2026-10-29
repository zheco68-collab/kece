-- KECE 性能优化与覆盖索引 - Schema v20
--
-- 1) submission 表增加联合覆盖索引 (user_id, status, question_id) 与反向聚合索引 (question_id, status)
--    消除列表状态判定与通过率统计的回表 IO。
-- 2) db_submission 表增加联合覆盖索引 (user_id, status, question_id)。
-- 3) exam_submission 表增加复合索引 (exam_id, user_id, exam_question_id)。

-- 1. 算法题提交表：联合覆盖索引 (user_id, status, question_id)
-- 用于加速 findAcceptedQuestionIds 与用户通过题集统计
ALTER TABLE `submission`
    ADD INDEX `idx_user_status_qid` (`user_id`, `status`, `question_id`);

-- 2. 算法题提交表：反向聚合索引 (question_id, status)
-- 用于加速题目通过率统计与出题人提交记录筛选
ALTER TABLE `submission`
    ADD INDEX `idx_qid_status` (`question_id`, `status`);

-- 3. SQL 数据库题提交表：联合覆盖索引 (user_id, status, question_id)
ALTER TABLE `db_submission`
    ADD INDEX `idx_user_status_qid` (`user_id`, `status`, `question_id`);

-- 4. 考试作答快照表：联合复合索引 (exam_id, user_id, exam_question_id)
ALTER TABLE `exam_submission`
    ADD INDEX `idx_exam_user_qid` (`exam_id`, `user_id`, `exam_question_id`);
