package com.kece.common.config;

import com.kece.database.entity.DbQuestion;
import com.kece.database.entity.DbTestCase;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.database.mapper.DbTestCaseMapper;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 数据库题示例数据播种：首次启动时写入一组基于 emp 经典 schema 的 SELECT/DML 题（含测试点）， 便于直接试用与验收。
 *
 * <p>受 {@code kece.demo-data.enabled} 控制（默认关、仅 dev profile 开）；在 {@link DataInitializer} 播种完 teacher
 * 账号后执行（{@code @Order(4)}）。
 *
 * <p>幂等策略：题目按标题精确查重，重复启动不产生重复数据；被出题人改动或删除的题不会被重新写回。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DbQuestionSeeder {

    private final DbQuestionMapper questionMapper;
    private final DbTestCaseMapper testCaseMapper;
    private final UserMapper userMapper;
    private final com.kece.judging.artifact.JudgeManifestMapper manifestMapper;
    private final com.kece.judging.artifact.JudgeManifestService judgeManifests;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    @Value("${kece.demo-data.enabled:false}")
    private boolean demoDataEnabled;

    @Order(4)
    @Bean
    public ApplicationRunner seedDbQuestions() {
        return args -> {
            if (!demoDataEnabled) {
                log.info("演示数据开关已关闭（kece.demo-data.enabled=false），跳过数据库题示例播种");
                return;
            }
            User teacher = userMapper.findByUsername("teacher");
            if (teacher == null) {
                log.warn("未找到 teacher 账号，跳过数据库题示例数据播种");
                return;
            }
            int created = 0;
            for (Seed seed : SEEDS) {
                created += ensureQuestion(seed, teacher.getId());
            }
            if (created > 0) {
                log.info("已播种 {} 道数据库题示例", created);
            }
        };
    }

    private int ensureQuestion(Seed seed, Long authorId) {
        DbQuestion existing = questionMapper.findByTitle(seed.title());
        if (existing != null) {
            if (manifestMapper.current(
                            com.kece.judging.JudgeDispatch.Kind.DATABASE.name(), existing.getId())
                    == null) {
                transaction.executeWithoutResult(
                        tx ->
                                judgeManifests.publish(
                                        com.kece.judging.JudgeDispatch.Kind.DATABASE,
                                        existing.getId()));
            }
            return 0;
        }
        return transaction.execute(
                tx -> {
                    DbQuestion question = new DbQuestion();
                    question.setTitle(seed.title());
                    question.setDescription(seed.description());
                    question.setDialect("MYSQL");
                    question.setType(seed.type());
                    question.setSchemaSql(seed.schemaSql());
                    question.setReferenceSql(seed.referenceSql());
                    question.setVerifySql(seed.verifySql());
                    question.setOrderSensitive(seed.orderSensitive() ? 1 : 0);
                    question.setStatus(1); // 示例题一律公开
                    question.setCreatedBy(authorId);
                    questionMapper.insert(question);
                    int order = 0;
                    for (Case c : seed.cases()) {
                        DbTestCase testCase = new DbTestCase();
                        testCase.setQuestionId(question.getId());
                        testCase.setCaseOrder(order++);
                        testCase.setSeedSql(c.seedSql());
                        testCase.setExpectedOutput(c.expectedOutput());
                        testCase.setIsSample(c.sample() ? 1 : 0);
                        testCaseMapper.insert(testCase);
                    }
                    judgeManifests.publish(
                            com.kece.judging.JudgeDispatch.Kind.DATABASE, question.getId());
                    return 1;
                });
    }

    /** 标题为查重键，务必保持稳定；改动标题等于新增一道题。 */
    private record Seed(
            String title,
            String description,
            String type,
            String schemaSql,
            String referenceSql,
            String verifySql,
            boolean orderSensitive,
            List<Case> cases) {}

    private record Case(String seedSql, String expectedOutput, boolean sample) {
        Case(String seedSql, boolean sample) {
            this(seedSql, null, sample);
        }
    }

    /** emp 经典 schema：三类题共用，测试点只带数据。 */
    private static final String EMP_SCHEMA =
            "CREATE TABLE emp (id INT PRIMARY KEY, name VARCHAR(32), dept VARCHAR(16), salary DECIMAL(10,2));";

    private static final String EMP_SEED_1 =
            "INSERT INTO emp VALUES (1,'Alice','RD',10000.00),(2,'Bob','RD',12000.00),(3,'Cara','HR',9000.00);";
    private static final String EMP_SEED_2 =
            "INSERT INTO emp VALUES (1,'Dan','RD',8000.00),(2,'Eve','HR',9500.00),(3,'Finn','HR',11000.00),(4,'Gail','OPS',7000.00);";

    private static final List<Seed> SEEDS =
            List.of(
                    new Seed(
                            "查询研发部员工",
                            """
                            ## 题目描述

                            员工表 `emp` 结构如下：

                            ```sql
                            CREATE TABLE emp (id INT PRIMARY KEY, name VARCHAR(32), dept VARCHAR(16), salary DECIMAL(10,2));
                            ```

                            编写 SQL 查询**研发部（dept = 'RD'）**所有员工的 `id` 与 `name`，列顺序为 `id, name`。

                            ## 输出要求

                            结果集与标准答案按行无序比对。
                            """,
                            "SELECT",
                            EMP_SCHEMA,
                            "SELECT id, name FROM emp WHERE dept = 'RD'",
                            null,
                            false,
                            List.of(
                                    new Case(
                                            EMP_SEED_1,
                                            "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"name\",\"type\":\"VARCHAR\"}],\"rows\":[[\"1\",\"Alice\"],[\"2\",\"Bob\"]]}",
                                            true),
                                    new Case(EMP_SEED_2, false))),
                    new Seed(
                            "统计各部门平均薪资",
                            """
                            ## 题目描述

                            员工表 `emp` 结构如下：

                            ```sql
                            CREATE TABLE emp (id INT PRIMARY KEY, name VARCHAR(32), dept VARCHAR(16), salary DECIMAL(10,2));
                            ```

                            编写 SQL 统计每个部门的平均薪资，输出两列：部门 `dept`、平均薪资 `avg_salary`（保留两位小数），
                            并按部门名**升序**排列。

                            ## 输出要求

                            结果集按行有序比对（ORDER BY dept）。
                            """,
                            "SELECT",
                            EMP_SCHEMA,
                            "SELECT dept, ROUND(AVG(salary), 2) AS avg_salary FROM emp GROUP BY dept ORDER BY dept",
                            null,
                            true,
                            List.of(
                                    new Case(
                                            EMP_SEED_1,
                                            "{\"columns\":[{\"name\":\"dept\",\"type\":\"VARCHAR\"},{\"name\":\"avg_salary\",\"type\":\"DECIMAL\"}],\"rows\":[[\"HR\",\"9000.00\"],[\"RD\",\"11000.00\"]]}",
                                            true),
                                    new Case(EMP_SEED_2, false))),
                    new Seed(
                            "给研发部员工涨薪 10%",
                            """
                            ## 题目描述

                            员工表 `emp` 结构如下：

                            ```sql
                            CREATE TABLE emp (id INT PRIMARY KEY, name VARCHAR(32), dept VARCHAR(16), salary DECIMAL(10,2));
                            ```

                            编写一条 UPDATE 语句，将**研发部（dept = 'RD'）**所有员工的薪资上调 10%。

                            ## 判分方式

                            执行你的语句后，系统用校验查询比对整表数据。
                            """,
                            "DML",
                            EMP_SCHEMA,
                            "UPDATE emp SET salary = salary * 1.1 WHERE dept = 'RD'",
                            "SELECT id, name, dept, salary FROM emp ORDER BY id",
                            true,
                            List.of(
                                    new Case(
                                            EMP_SEED_1,
                                            "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"name\",\"type\":\"VARCHAR\"},{\"name\":\"dept\",\"type\":\"VARCHAR\"},{\"name\":\"salary\",\"type\":\"DECIMAL\"}],\"rows\":[[\"1\",\"Alice\",\"RD\",\"11000.00\"],[\"2\",\"Bob\",\"RD\",\"13200.00\"],[\"3\",\"Cara\",\"HR\",\"9000.00\"]]}",
                                            true),
                                    new Case(EMP_SEED_2, false))),
                    new Seed(
                            "部门与员工信息联合查询",
                            """
                            ## 题目要求

                            编写 SQL，完成针对【部门与员工信息联合查询】的相关查询。

                            ## 数据库表结构

                            ### `dept` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 部门主键编号 |
                            | `dept_name` | `VARCHAR(32)` | 部门名称 |

                            ### `emp` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 员工主键编号 |
                            | `name` | `VARCHAR(32)` | 员工姓名 |
                            | `dept_id` | `INT` | 所属部门编号（未分配时为 NULL） |
                            | `salary` | `DECIMAL(10,2)` | 薪资 |

                            ## 查询要求

                            编写 SQL 查询每个员工的 `id`、姓名 `name`、所属部门名称 `dept_name`（如果员工尚未分配部门，则部门名称显示为 NULL）以及薪资 `salary`。

                            结果按员工 `id` **升序**排列。
                            """,
                            "SELECT",
                            """
                            CREATE TABLE dept (
                              id INT PRIMARY KEY,
                              dept_name VARCHAR(32) NOT NULL
                            );

                            CREATE TABLE emp (
                              id INT PRIMARY KEY,
                              name VARCHAR(32) NOT NULL,
                              dept_id INT,
                              salary DECIMAL(10,2) NOT NULL
                            );
                            """,
                            "SELECT e.id, e.name, d.dept_name, e.salary FROM emp e LEFT JOIN dept d ON e.dept_id = d.id ORDER BY e.id;",
                            null,
                            true,
                            List.of(
                                    new Case(
                                            """
                                            INSERT INTO dept VALUES (1, '研发部'), (2, '运营部'), (3, '财务部');
                                            INSERT INTO emp VALUES (1, '张三', 1, 12000.00), (2, '李四', 1, 15000.00), (3, '王五', 2, 9000.00), (4, '赵六', NULL, 8000.00);
                                            """,
                                            "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"name\",\"type\":\"VARCHAR\"},{\"name\":\"dept_name\",\"type\":\"VARCHAR\"},{\"name\":\"salary\",\"type\":\"DECIMAL\"}],\"rows\":[[\"1\",\"张三\",\"研发部\",\"12000.00\"],[\"2\",\"李四\",\"研发部\",\"15000.00\"],[\"3\",\"王五\",\"运营部\",\"9000.00\"],[\"4\",\"赵六\",\"NULL\",\"8000.00\"]]}",
                                            true),
                                    new Case(
                                            """
                                            INSERT INTO dept VALUES (10, '市场部');
                                            INSERT INTO emp VALUES (101, 'Alex', NULL, 6000.00), (102, 'Bob', 10, 7500.00), (103, 'Cindy', 99, 8800.00);
                                            """,
                                            false))),
                    new Seed(
                            "查找人数大于1人的部门",
                            """
                            ## 题目要求

                            编写 SQL，完成针对【查找人数大于1人的部门】的相关统计分析。

                            ## 数据库表结构

                            ### `department` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 部门主键编号 |
                            | `name` | `VARCHAR(32)` | 部门名称 |

                            ### `employee` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 员工主键编号 |
                            | `name` | `VARCHAR(32)` | 员工姓名 |
                            | `department_id` | `INT` | 所属部门编号 |

                            ## 查询要求

                            编写 SQL 查询员工人数大于 1 人的部门名称 `name` 及其对应的员工人数 `emp_count`。

                            结果先按员工人数 `emp_count` **降序**排列；若人数相同，按部门名称 `name` **升序**排列。
                            """,
                            "SELECT",
                            """
                            CREATE TABLE department (
                              id INT PRIMARY KEY,
                              name VARCHAR(32) NOT NULL
                            );

                            CREATE TABLE employee (
                              id INT PRIMARY KEY,
                              name VARCHAR(32) NOT NULL,
                              department_id INT
                            );
                            """,
                            "SELECT d.name, COUNT(e.id) AS emp_count FROM department d JOIN employee e ON d.id = e.department_id GROUP BY d.id, d.name HAVING COUNT(e.id) > 1 ORDER BY emp_count DESC, d.name ASC;",
                            null,
                            true,
                            List.of(
                                    new Case(
                                            """
                                            INSERT INTO department VALUES (1, '技术部'), (2, '人事部'), (3, '销售部');
                                            INSERT INTO employee VALUES (1, 'Alice', 1), (2, 'Bob', 1), (3, 'Charlie', 1), (4, 'David', 2), (5, 'Emma', 2), (6, 'Frank', 3);
                                            """,
                                            "{\"columns\":[{\"name\":\"name\",\"type\":\"VARCHAR\"},{\"name\":\"emp_count\",\"type\":\"BIGINT\"}],\"rows\":[[\"技术部\",\"3\"],[\"人事部\",\"2\"]]}",
                                            true),
                                    new Case(
                                            """
                                            INSERT INTO department VALUES (1, 'A'), (2, 'B'), (3, 'C');
                                            INSERT INTO employee VALUES (1, 'E1', 1), (2, 'E2', 2);
                                            """,
                                            false))),
                    new Seed(
                            "超过经理收入的员工",
                            """
                            ## 题目要求

                            编写 SQL，完成针对【超过经理收入的员工】的查询。

                            ## 数据库表结构

                            ### `Employee` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 员工编号主键 |
                            | `name` | `VARCHAR(32)` | 员工姓名 |
                            | `salary` | `DECIMAL(10,2)` | 薪水 |
                            | `manager_id` | `INT` | 其直接经理的编号（无经理为 NULL） |

                            ## 查询要求

                            编写 SQL 查询薪资超过其直接主管的员工姓名 `Employee`（输出列名命名为 `Employee`）。

                            结果对行顺序无敏感要求。
                            """,
                            "SELECT",
                            """
                            CREATE TABLE Employee (
                              id INT PRIMARY KEY,
                              name VARCHAR(32) NOT NULL,
                              salary DECIMAL(10,2) NOT NULL,
                              manager_id INT
                            );
                            """,
                            "SELECT e.name AS Employee FROM Employee e JOIN Employee m ON e.manager_id = m.id WHERE e.salary > m.salary;",
                            null,
                            false,
                            List.of(
                                    new Case(
                                            "INSERT INTO Employee VALUES (1, 'Joe', 70000.00, 3), (2, 'Henry', 80000.00, 4), (3, 'Sam', 60000.00, NULL), (4, 'Max', 90000.00, NULL);",
                                            "{\"columns\":[{\"name\":\"Employee\",\"type\":\"VARCHAR\"}],\"rows\":[[\"Joe\"]]}",
                                            true),
                                    new Case(
                                            "INSERT INTO Employee VALUES (1, 'A', 50000.00, 2), (2, 'B', 50000.00, NULL), (3, 'C', 60000.00, 2);",
                                            false))),
                    new Seed(
                            "删除没有员工的空部门",
                            """
                            ## 题目要求

                            编写一条数据变更（DML）语句，清理废弃空部门数据。

                            ## 数据库表结构

                            ### `dept` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 部门编号主键 |
                            | `name` | `VARCHAR(32)` | 部门名称 |

                            ### `emp` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 员工编号主键 |
                            | `name` | `VARCHAR(32)` | 员工姓名 |
                            | `dept_id` | `INT` | 所属部门编号 |

                            ## 操作要求

                            编写一条 `DELETE` 语句，删除 `dept` 表中没有任何员工归属的部门（即该部门的 `id` 未在 `emp` 表的 `dept_id` 中被引用）。

                            ## 判分方式

                            系统在执行你的语句后，将运行 `SELECT id, name FROM dept ORDER BY id;` 查询保留的部门列表进行完整比对。
                            """,
                            "DML",
                            """
                            CREATE TABLE dept (
                              id INT PRIMARY KEY,
                              name VARCHAR(32) NOT NULL
                            );

                            CREATE TABLE emp (
                              id INT PRIMARY KEY,
                              name VARCHAR(32) NOT NULL,
                              dept_id INT
                            );
                            """,
                            "DELETE FROM dept WHERE id NOT IN (SELECT DISTINCT dept_id FROM emp WHERE dept_id IS NOT NULL);",
                            "SELECT id, name FROM dept ORDER BY id;",
                            true,
                            List.of(
                                    new Case(
                                            """
                                            INSERT INTO dept VALUES (1, '研发部'), (2, '测试部'), (3, '闲置部'), (4, '已撤销部');
                                            INSERT INTO emp VALUES (1, 'Tom', 1), (2, 'Jerry', 1), (3, 'Spike', 2);
                                            """,
                                            "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"name\",\"type\":\"VARCHAR\"}],\"rows\":[[\"1\",\"研发部\"],[\"2\",\"测试部\"]]}",
                                            true),
                                    new Case(
                                            """
                                            INSERT INTO dept VALUES (10, 'D1'), (20, 'D2');
                                            INSERT INTO emp VALUES (1, 'P1', 10), (2, 'P2', 20);
                                            """,
                                            false))),
                    new Seed(
                            "各个部门薪资最高的员工",
                            """
                            ## 题目要求

                            编写 SQL，完成针对【各个部门薪资最高的员工】的分组极值查询。

                            ## 数据库表结构

                            ### `Department` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 部门编号主键 |
                            | `name` | `VARCHAR(32)` | 部门名称 |

                            ### `Employee` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 员工编号主键 |
                            | `name` | `VARCHAR(32)` | 员工姓名 |
                            | `salary` | `DECIMAL(10,2)` | 薪资 |
                            | `department_id` | `INT` | 所属部门编号 |

                            ## 查询要求

                            编写 SQL 查询每个部门中薪资最高的员工。

                            返回结果包含三列：部门名称 `Department`、员工姓名 `Employee` 和薪资 `Salary`。如果某个部门中有多名员工并列最高薪资，则他们都应该出现在查询结果中。
                            """,
                            "SELECT",
                            """
                            CREATE TABLE Department (
                              id INT PRIMARY KEY,
                              name VARCHAR(32) NOT NULL
                            );

                            CREATE TABLE Employee (
                              id INT PRIMARY KEY,
                              name VARCHAR(32) NOT NULL,
                              salary DECIMAL(10,2) NOT NULL,
                              department_id INT
                            );
                            """,
                            """
                            SELECT d.name AS Department, e.name AS Employee, e.salary AS Salary
                            FROM Employee e
                            JOIN Department d ON e.department_id = d.id
                            WHERE (e.department_id, e.salary) IN (
                                SELECT department_id, MAX(salary)
                                FROM Employee
                                GROUP BY department_id
                            );
                            """,
                            null,
                            false,
                            List.of(
                                    new Case(
                                            """
                                            INSERT INTO Department VALUES (1, 'IT'), (2, 'Sales');
                                            INSERT INTO Employee VALUES (1, 'Joe', 85000.00, 1), (2, 'Henry', 80000.00, 2), (3, 'Sam', 60000.00, 2), (4, 'Max', 90000.00, 1), (5, 'Janet', 69000.00, 1), (6, 'Randy', 85000.00, 1);
                                            """,
                                            "{\"columns\":[{\"name\":\"Department\",\"type\":\"VARCHAR\"},{\"name\":\"Employee\",\"type\":\"VARCHAR\"},{\"name\":\"Salary\",\"type\":\"DECIMAL\"}],\"rows\":[[\"IT\",\"Max\",\"90000.00\"],[\"Sales\",\"Henry\",\"80000.00\"]]}",
                                            true),
                                    new Case(
                                            """
                                            INSERT INTO Department VALUES (1, 'HR');
                                            INSERT INTO Employee VALUES (1, 'A', 10000.00, 1), (2, 'B', 10000.00, 1);
                                            """,
                                            false))),
                    new Seed(
                            "合格候选人录入员工表",
                            """
                            ## 题目要求

                            编写一条数据插入（DML INSERT）语句，将考核合格的候选人批量归档至新员工表中。

                            ## 数据库表结构

                            ### `candidates` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 候选人编号主键 |
                            | `name` | `VARCHAR(32)` | 姓名 |
                            | `score` | `INT` | 综合考核分数 |
                            | `dept` | `VARCHAR(16)` | 拟录用部门 |

                            ### `new_hires` 表：

                            | 字段名 | 类型 | 说明 |
                            | :--- | :--- | :--- |
                            | `id` | `INT` | 员工编号主键 |
                            | `name` | `VARCHAR(32)` | 姓名 |
                            | `dept` | `VARCHAR(16)` | 录用部门 |
                            | `salary` | `DECIMAL(10,2)` | 初始月薪 |

                            ## 操作要求

                            编写一条 `INSERT INTO ... SELECT` 语句，将所有考核分数大于等于 80 分（`score >= 80`）的候选人录入 `new_hires` 表，其月薪 `salary` 统一设定为 `9000.00`。

                            ## 判分方式

                            系统执行你的语句后，将运行 `SELECT id, name, dept, salary FROM new_hires ORDER BY id;` 进行整表核对。
                            """,
                            "DML",
                            """
                            CREATE TABLE candidates (
                              id INT PRIMARY KEY,
                              name VARCHAR(32) NOT NULL,
                              score INT NOT NULL,
                              dept VARCHAR(16) NOT NULL
                            );

                            CREATE TABLE new_hires (
                              id INT PRIMARY KEY,
                              name VARCHAR(32) NOT NULL,
                              dept VARCHAR(16) NOT NULL,
                              salary DECIMAL(10,2) NOT NULL
                            );
                            """,
                            "INSERT INTO new_hires (id, name, dept, salary) SELECT id, name, dept, 9000.00 FROM candidates WHERE score >= 80;",
                            "SELECT id, name, dept, salary FROM new_hires ORDER BY id;",
                            true,
                            List.of(
                                    new Case(
                                            "INSERT INTO candidates VALUES (101, 'Kevin', 85, 'Dev'), (102, 'Lucy', 72, 'Dev'), (103, 'Mike', 92, 'QA'), (104, 'Nancy', 60, 'HR');",
                                            "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"name\",\"type\":\"VARCHAR\"},{\"name\":\"dept\",\"type\":\"VARCHAR\"},{\"name\":\"salary\",\"type\":\"DECIMAL\"}],\"rows\":[[\"101\",\"Kevin\",\"Dev\",\"9000.00\"],[\"103\",\"Mike\",\"QA\",\"9000.00\"]]}",
                                            true),
                                    new Case(
                                            "INSERT INTO candidates VALUES (201, 'P1', 79, 'Ops'), (202, 'P2', 80, 'Ops');",
                                            false))));
}
