-- ==============================================================================
-- KECE 评测平台 - ArkTS 题库数据导入脚本
-- 提取自：《张哲 25304002(1).docx》
-- 涵盖：5道填空题 + 5道判断题 + 5道单选题 + 2道改编编程题及完整评测测试点
-- ==============================================================================

-- 1. 客观题表导入 (objective_question)
-- 填空题 (FILL)
INSERT INTO `objective_question` (`type`, `content`, `options`, `answer`, `match_type`, `score`, `created_by`, `status`) VALUES
('FILL', '在 ArkTS 中定义变量时，数字类型使用 `____` 关键字表示。', NULL, 'number', 'IGNORE_CASE', 5, 2, 1),
('FILL', '在 ArkTS 中定义变量时，字符串类型使用 `____` 关键字表示。', NULL, 'string', 'IGNORE_CASE', 5, 2, 1),
('FILL', '在 ArkTS 中声明常量所使用的关键字是 `____`。', NULL, 'const', 'EXACT', 5, 2, 1),
('FILL', '在 ArkTS 中，如果一个变量有多种类型（联合类型），可以使用 `____`（符号）分隔不同的类型。', NULL, '|', 'EXACT', 5, 2, 1),
('FILL', '在 ArkTS 中，三元条件运算符包括冒号 `:` 以及 `____`（符号）。', NULL, '?', 'EXACT', 5, 2, 1);

-- 判断题 (按单选 SINGLE 存储，选项 A: 正确 / B: 错误)
INSERT INTO `objective_question` (`type`, `content`, `options`, `answer`, `match_type`, `score`, `created_by`, `status`) VALUES
('SINGLE', 'ArkTS 支持 TypeScript 的所有语法。', '["A. 正确", "B. 错误"]', 'B', 'EXACT', 5, 2, 1),
('SINGLE', '在 ArkTS 中定义变量时，变量的类型在任何情况下都不能省略。', '["A. 正确", "B. 错误"]', 'B', 'EXACT', 5, 2, 1),
('SINGLE', '在 ArkTS 中，`object` 表示对象，它是一种引用数据类型。', '["A. 正确", "B. 错误"]', 'A', 'EXACT', 5, 2, 1),
('SINGLE', '在 ArkTS 中，用于定义函数的关键字是 `class`。', '["A. 正确", "B. 错误"]', 'B', 'EXACT', 5, 2, 1),
('SINGLE', '在 ArkTS 声明变量时，如果类型不确定，可以使用 `any` 类型。', '["A. 正确", "B. 错误"]', 'B', 'EXACT', 5, 2, 1);

-- 单选题 (SINGLE)
INSERT INTO `objective_question` (`type`, `content`, `options`, `answer`, `match_type`, `score`, `created_by`, `status`) VALUES
('SINGLE', '下列选项中，不属于 ArkTS 中的原始数据类型的是（ ）。', '["A. number", "B. boolean", "C. integer", "D. string"]', 'C', 'EXACT', 5, 2, 1),
('SINGLE', '下列选项中，属于循环语句的是（ ）。', '["A. if 语句", "B. switch 语句", "C. if...else if...else 语句", "D. for 语句"]', 'D', 'EXACT', 5, 2, 1),
('SINGLE', '下列选项中，属于跳转语句的是（ ）。', '["A. while 语句", "B. if...else 语句", "C. break 语句", "D. switch 语句"]', 'C', 'EXACT', 5, 2, 1),
('SINGLE', '下列选项中，正确声明并初始化了数字数组的语句是（ ）。', '["A. int arr = [1, 2, 3];", "B. var arr = [1, 2, 3];", "C. let arr: number[] = [1, 2, 3];", "D. number[] arr = [1, 2, 3];"]', 'C', 'EXACT', 5, 2, 1),
('SINGLE', '下列选项中，关于函数的说法错误的是（ ）。', '["A. 函数名不能以数字开头", "B. 一个函数至少要有一个参数", "C. 函数可以没有返回值", "D. 函数的返回值使用 return 关键字返回"]', 'B', 'EXACT', 5, 2, 1);

-- 简答题核心概念填空 (FILL)
INSERT INTO `objective_question` (`type`, `content`, `options`, `answer`, `match_type`, `score`, `created_by`, `status`) VALUES
('FILL', '在 ArkTS 中，用于设置延时执行一次的定时器全局函数是 `____()`。', NULL, 'setTimeout', 'IGNORE_CASE', 5, 2, 1),
('FILL', '在 ArkTS 中，箭头函数是函数的一种简写形式，使用的核心连接符号是 `____`。', NULL, '=>', 'EXACT', 5, 2, 1);


-- 2. 编程题表导入 (question) 与测试用例 (test_case)

-- 编程题 1：圆的周长与面积
INSERT INTO `question` (`title`, `description`, `difficulty`, `time_limit`, `memory_limit`, `languages`, `created_by`, `status`)
VALUES (
    '计算圆的周长与面积',
    '## 题目描述\n\n给定圆的半径 $r$，请利用算术运算符计算并输出该圆的周长 $C$ 与面积 $S$。\n\n计算公式：\n- 周长公式：$C = 2 \\times \\pi \\times r$\n- 面积公式：$S = \\pi \\times r^2$\n\n其中圆周率取 $\\pi = 3.14159$。\n\n## 输入格式\n\n一行一个正数 $r$（$0.1 \\le r \\le 1000$），表示圆的半径。\n\n## 输出格式\n\n输出两行：\n- 第一行输出一个浮点数，表示圆的周长；\n- 第二行输出一个浮点数，表示圆的面积。',
    'EASY',
    1000,
    256,
    'cpp,java,python,arkts',
    2,
    1
);

SET @q1_id = LAST_INSERT_ID();

-- 编程题 1 的测试用例（公开样例 + 隐藏评测点）
INSERT INTO `test_case` (`question_id`, `input`, `expected_output`, `is_sample`) VALUES
(@q1_id, '5\n', '31.4159\n78.53975', 1),
(@q1_id, '1\n', '6.28318\n3.14159', 1),
(@q1_id, '10\n', '62.8318\n314.159', 0),
(@q1_id, '2.5\n', '15.70795\n19.6349375', 0),
(@q1_id, '100\n', '628.318\n31415.9', 0);


-- 编程题 2：连续整数之和
INSERT INTO `question` (`title`, `description`, `difficulty`, `time_limit`, `memory_limit`, `languages`, `created_by`, `status`)
VALUES (
    '连续整数求和',
    '## 题目描述\n\n给定一个正整数 $n$，请编写程序计算从 $1$ 到 $n$ 的所有整数之和，即：\n$$S = 1 + 2 + 3 + \\dots + n$$\n\n请使用循环语句（如 `for` 或 `while`）或数学方法实现累加求和。\n\n## 输入格式\n\n一行一个正整数 $n$（$1 \\le n \\le 10^5$）。\n\n## 输出格式\n\n一行一个整数，表示 $1 \\sim n$ 的所有整数累加和。',
    'EASY',
    1000,
    256,
    'cpp,java,python,arkts',
    2,
    1
);

SET @q2_id = LAST_INSERT_ID();

-- 编程题 2 的测试用例（公开样例 + 隐藏评测点）
INSERT INTO `test_case` (`question_id`, `input`, `expected_output`, `is_sample`) VALUES
(@q2_id, '100\n', '5050', 1),
(@q2_id, '10\n', '55', 1),
(@q2_id, '1\n', '1', 0),
(@q2_id, '1000\n', '500500', 0),
(@q2_id, '666\n', '222111', 0),
(@q2_id, '100000\n', '5000050000', 0);

