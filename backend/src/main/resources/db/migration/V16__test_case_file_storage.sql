-- V16: 编程评测点文件存储改造与大测试集支持
ALTER TABLE `test_case`
    ADD COLUMN `input_path` VARCHAR(255) NULL COMMENT '输入文件相对路径' AFTER `question_id`,
    ADD COLUMN `output_path` VARCHAR(255) NULL COMMENT '输出文件相对路径' AFTER `input_path`,
    ADD COLUMN `input_size` BIGINT NOT NULL DEFAULT 0 COMMENT '输入文件大小(字节)' AFTER `output_path`,
    ADD COLUMN `output_size` BIGINT NOT NULL DEFAULT 0 COMMENT '输出文件大小(字节)' AFTER `input_size`,
    ADD COLUMN `case_num` INT NOT NULL DEFAULT 1 COMMENT '用例序号' AFTER `output_size`,
    ADD COLUMN `score_weight` INT NOT NULL DEFAULT 10 COMMENT '测试点分值权重' AFTER `case_num`,
    ADD INDEX `idx_question_case` (`question_id`, `case_num`);
