-- V17: 允许测试用例期望输出为空（文件系统独立托管，DB仅保留元数据）
ALTER TABLE `test_case` MODIFY COLUMN `expected_output` MEDIUMTEXT NULL COMMENT '期望输出数据（文件系统托管，DB仅样例保留文本）';

-- 清空已由文件系统托管的非样例测试点的大文本数据，减轻 MySQL 磁盘与内存压力
UPDATE `test_case`
SET `input` = NULL, `expected_output` = NULL
WHERE (`is_sample` = 0 OR `is_sample` IS NULL)
  AND `input_path` IS NOT NULL
  AND `input_path` != '';
