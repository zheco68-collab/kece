-- =========================================================================
-- V22: KECE 随堂课练模块底层架构重构
-- 1. lesson_practice_question 增加题目快照 JSON，防止原题篡改或下架破坏历史
-- 2. lesson_practice_member 增加罚时与活跃追踪字段，补充复合索引支撑实时记分板
-- 3. lesson_practice_submission 补充更新时间与全局 submission_id 反查联合索引
-- =========================================================================

-- 1. 题目表：增加题目快照字段（题面、选项、标准答案、规格）
ALTER TABLE `lesson_practice_question`
    ADD COLUMN `snapshot_json` MEDIUMTEXT DEFAULT NULL COMMENT '题目核心元数据快照' AFTER `sort_order`;

-- 2. 成员表：增加累计罚时与最后活跃时间，建立实时记分板高效排序索引
ALTER TABLE `lesson_practice_member`
    ADD COLUMN `penalty_time` BIGINT NOT NULL DEFAULT 0 COMMENT '累计罚时(秒)' AFTER `solved_count`,
    ADD COLUMN `last_active_at` DATETIME DEFAULT NULL COMMENT '最后答题或心跳时间' AFTER `submitted_at`,
    ADD INDEX `idx_pm_practice_score` (`practice_id`, `total_score` DESC, `penalty_time` ASC);

-- 3. 提交表：补充更新时间与全局 submission_id 反查联合索引（支撑 BRPOP 监听器 O(1) 反查）
ALTER TABLE `lesson_practice_submission`
    ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `created_at`,
    ADD INDEX `idx_sub_id_type` (`submission_id`, `question_type`),
    ADD INDEX `idx_practice_user_score` (`practice_id`, `user_id`, `score`);

