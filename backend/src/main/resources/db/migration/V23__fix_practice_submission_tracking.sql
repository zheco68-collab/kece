-- =========================================================================
-- V23: 修复随堂课练提交记录追踪（保留最高分源码快照、真实提交次数、首次达成时间）
-- =========================================================================

ALTER TABLE `lesson_practice_submission`
    ADD COLUMN `best_submission_id` BIGINT DEFAULT NULL COMMENT '最高分/首次AC对应的评测记录ID' AFTER `submission_id`,
    ADD COLUMN `attempt_count` INT NOT NULL DEFAULT 1 COMMENT '累计尝试提交次数' AFTER `score`,
    ADD COLUMN `best_achieved_at` DATETIME DEFAULT NULL COMMENT '首次达成当前最高分时间' AFTER `created_at`;

-- 回填历史存量数据
UPDATE `lesson_practice_submission`
SET `best_submission_id` = `submission_id`
WHERE `submission_id` IS NOT NULL AND `best_submission_id` IS NULL;

UPDATE `lesson_practice_submission`
SET `best_achieved_at` = COALESCE(`updated_at`, `created_at`)
WHERE `score` > 0 AND `best_achieved_at` IS NULL;

