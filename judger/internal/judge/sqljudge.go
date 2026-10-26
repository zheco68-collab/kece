package judge

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/go-sql-driver/mysql"

	"github.com/zheco68-collab/KECE/judger/internal/compare"
	"github.com/zheco68-collab/KECE/judger/internal/config"
	"github.com/zheco68-collab/KECE/judger/internal/model"
	"github.com/zheco68-collab/KECE/judger/internal/queue"
	"github.com/zheco68-collab/KECE/judger/internal/sandbox"
)

const maxResultRows = 100 // 结果集展示最多保留行数

type sqlExecutor interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
}

// ProcessSQL 处理一次数据库评测：逐用例建库、seed、运行参考与用户 SQL、比对结果集并回写终态。
func (j *Judge) ProcessSQL(ctx context.Context, sqlQ *queue.Queue, task model.Task) {
	sqlText := extractSQL(task)
	if sqlText == "" {
		j.finishSQLSystemError(ctx, sqlQ, task.SubmissionID)
		return
	}
	adminPool, userPool, err := j.getSQLPools()
	if err != nil {
		log.Printf("sqljudge: getSQLPools failed: %v", err)
		j.finishSQLSystemError(ctx, sqlQ, task.SubmissionID)
		return
	}
	adminConn, err := adminPool.Conn(ctx)
	if err != nil {
		log.Printf("sqljudge: acquire adminConn failed: %v", err)
		j.finishSQLSystemError(ctx, sqlQ, task.SubmissionID)
		return
	}
	defer adminConn.Close()

	userConn, err := userPool.Conn(ctx)
	if err != nil {
		log.Printf("sqljudge: acquire userConn failed: %v", err)
		j.finishSQLSystemError(ctx, sqlQ, task.SubmissionID)
		return
	}
	defer userConn.Close()

	j.publishSQL(ctx, sqlQ, task.SubmissionID, statusJudging, len(task.TestCases), 0)

	report := make([]model.CaseResult, 0, len(task.TestCases))
	statuses := make([]string, 0, len(task.TestCases))
	start := time.Now()
	for i, tc := range task.TestCases {
		caseStart := time.Now()
		cr, err := j.runSQLCase(ctx, adminConn, userConn, task, tc, sqlText)
		if err != nil {
			log.Printf("sqljudge: runSQLCase failed on case %d: %v", i+1, err)
			j.finishSQLSystemError(ctx, sqlQ, task.SubmissionID)
			return
		}
		// 与代码题一致：逐用例耗时（含建库/建表/灌数据），供前端测试点状态方块展示。
		cr.TimeMs = intPtr(int(time.Since(caseStart).Milliseconds()))
		report = append(report, cr)
		statuses = append(statuses, cr.Status)
		j.publishSQL(ctx, sqlQ, task.SubmissionID, statusJudging, len(task.TestCases), i+1)
	}

	overall := AggregateOverall(statuses)
	result := model.Result{
		Score:           intPtr(scoreFromCases(task.TestCases, statuses)),
		TimeUsedMs:      intPtr(int(time.Since(start).Milliseconds())),
		TestCasesReport: report,
	}
	j.finishSQLTerminal(ctx, sqlQ, task.SubmissionID, overall, result)
}

// runSQLCase 运行单个测试点：建库→DDL/seed→计算期望→运行用户 SQL→计算实际→比对。
// 返回错误表示判题机自身或教师脚本出错（整题 SYSTEM_ERROR），用户 SQL 错误仅体现在 CaseResult。
func (j *Judge) runSQLCase(
	ctx context.Context,
	adminConn, userConn sqlExecutor,
	task model.Task,
	tc model.TestCase,
	sqlText string,
) (model.CaseResult, error) {
	// 单个用例的总时限覆盖建库/DDL/seed/参考与用户 SQL，避免教师脚本或用户 SQL 无限挂起。
	timeout := time.Duration(task.TimeLimitMs) * time.Millisecond
	if timeout <= 0 {
		timeout = j.cfg.SqlTimeout
	}
	caseCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	dbName := fmt.Sprintf("judge_%d_%d", task.SubmissionID, tc.CaseID)
	if err := createDatabase(caseCtx, adminConn, dbName); err != nil {
		return model.CaseResult{}, fmt.Errorf("创建临时库失败: %w", err)
	}
	// 清理用独立上下文，避免用例超时后残留临时库。
	defer func() {
		cleanupCtx, cancelCleanup := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		defer cancelCleanup()
		_ = dropDatabase(cleanupCtx, adminConn, dbName)
	}()

	if err := execScript(caseCtx, adminConn, dbName, task.SchemaSql); err != nil {
		return model.CaseResult{}, fmt.Errorf("建表失败: %w", err)
	}
	if err := execScript(caseCtx, adminConn, dbName, tc.SeedSql); err != nil {
		return model.CaseResult{}, fmt.Errorf("灌数据失败: %w", err)
	}

	expected, err := expectedResultSet(caseCtx, adminConn, dbName, task)
	if err != nil {
		return model.CaseResult{}, fmt.Errorf("计算期望结果失败: %w", err)
	}

	cr := model.CaseResult{CaseID: tc.CaseID}
	actual, userErr := actualResultSet(caseCtx, adminConn, userConn, dbName, task, tc.SeedSql, sqlText)
	if userErr != nil {
		if errors.Is(userErr, context.DeadlineExceeded) || caseCtx.Err() != nil {
			cr.Status = sandbox.StatusTimeLimitExceeded
		} else {
			cr.Status = sandbox.StatusWrongAnswer
			cr.ExpectedOutput = strPtr(truncateResultSet(expected))
			cr.ActualOutput = strPtr(truncate(userErr.Error(), 2000))
		}
		return cr, nil
	}

	if ok, diff := compare.EqualResultSets(expected, actual, task.OrderSensitive); ok {
		cr.Status = sandbox.StatusAccepted
	} else {
		_ = diff
		cr.Status = sandbox.StatusWrongAnswer
		cr.ExpectedOutput = strPtr(truncateResultSet(expected))
		cr.ActualOutput = strPtr(truncateResultSet(actual))
	}
	return cr, nil
}

// expectedResultSet 计算期望结果集：DML 题先跑标准答案再跑校验 SQL；SELECT 题直接跑标准答案。
func expectedResultSet(ctx context.Context, db sqlExecutor, dbName string, task model.Task) (compare.ResultSet, error) {
	if strings.TrimSpace(task.VerifySql) != "" {
		if err := execScript(ctx, db, dbName, task.ReferenceSql); err != nil {
			return compare.ResultSet{}, fmt.Errorf("标准答案运行失败: %w", err)
		}
		return queryResultSet(ctx, db, dbName, task.VerifySql)
	}
	return queryResultSet(ctx, db, dbName, task.ReferenceSql)
}

// actualResultSet 计算实际结果集：DML 题先重置再跑用户 SQL 再跑校验 SQL；SELECT 题直接跑用户 SQL。
func actualResultSet(
	ctx context.Context,
	adminConn, userConn sqlExecutor,
	dbName string,
	task model.Task,
	seedSql, sqlText string,
) (compare.ResultSet, error) {
	if strings.TrimSpace(task.VerifySql) != "" {
		if err := resetDatabase(ctx, adminConn, dbName, task.SchemaSql, seedSql); err != nil {
			return compare.ResultSet{}, err
		}
		if err := execUserSQL(ctx, userConn, dbName, sqlText); err != nil {
			return compare.ResultSet{}, err
		}
		return queryResultSet(ctx, adminConn, dbName, task.VerifySql)
	}
	return queryResultSet(ctx, userConn, dbName, sqlText)
}

// resetDatabase 重建临时库并重放表结构与数据，使用户 SQL 与标准答案处于同一初始状态。
func resetDatabase(ctx context.Context, db sqlExecutor, dbName, schemaSql, seedSql string) error {
	if err := dropDatabase(ctx, db, dbName); err != nil {
		return err
	}
	if err := createDatabase(ctx, db, dbName); err != nil {
		return err
	}
	if err := execScript(ctx, db, dbName, schemaSql); err != nil {
		return fmt.Errorf("重建表失败: %w", err)
	}
	if err := execScript(ctx, db, dbName, seedSql); err != nil {
		return fmt.Errorf("重建数据失败: %w", err)
	}
	return nil
}

// createDatabase 创建一次性临时库（库名由判题机生成，不含用户输入）。
func createDatabase(ctx context.Context, db sqlExecutor, name string) error {
	_, err := db.ExecContext(ctx, "CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
	return err
}

func dropDatabase(ctx context.Context, db sqlExecutor, name string) error {
	_, err := db.ExecContext(ctx, "DROP DATABASE IF EXISTS `"+name+"`")
	return err
}

// useDatabase 将连接切换到目标库；每条评测使用独占 sql.Conn，故 USE 对后续语句持续生效。
func useDatabase(ctx context.Context, db sqlExecutor, name string) error {
	_, err := db.ExecContext(ctx, "USE `"+name+"`")
	return err
}

// execScript 在指定库上执行教师脚本（DDL/seed/reference），空脚本为无操作。
// 仅用于 adminConn（multiStatements=true），允许多条语句。
func execScript(ctx context.Context, db sqlExecutor, dbName, script string) error {
	if strings.TrimSpace(script) == "" {
		return nil
	}
	if err := useDatabase(ctx, db, dbName); err != nil {
		return err
	}
	_, err := db.ExecContext(ctx, script)
	return err
}

// execUserSQL 在指定库上执行用户 SQL（单条语句，userConn 未开 multiStatements）。
func execUserSQL(ctx context.Context, db sqlExecutor, dbName, sqlText string) error {
	if err := useDatabase(ctx, db, dbName); err != nil {
		return err
	}
	_, err := db.ExecContext(ctx, sqlText)
	return err
}

func queryResultSet(ctx context.Context, db sqlExecutor, dbName, query string) (compare.ResultSet, error) {
	if err := useDatabase(ctx, db, dbName); err != nil {
		return compare.ResultSet{}, err
	}
	rows, err := db.QueryContext(ctx, query)
	if err != nil {
		return compare.ResultSet{}, err
	}
	defer rows.Close()
	return compare.Capture(rows)
}

// getSQLPools 获取或惰性初始化常驻的判题 MySQL 连接池（admin 多语句池与 user 单语句池）。
func (j *Judge) getSQLPools() (*sql.DB, *sql.DB, error) {
	j.sqlMu.Lock()
	defer j.sqlMu.Unlock()
	if j.adminPool != nil && j.userPool != nil {
		if err := j.adminPool.Ping(); err == nil {
			if err := j.userPool.Ping(); err == nil {
				return j.adminPool, j.userPool, nil
			}
		}
		_ = j.adminPool.Close()
		_ = j.userPool.Close()
		j.adminPool = nil
		j.userPool = nil
	}
	admin, user, err := openSQLConnections(j.cfg)
	if err != nil {
		return nil, nil, err
	}
	j.adminPool = admin
	j.userPool = user
	return admin, user, nil
}

// openSQLConnections 建立判题 MySQL 的 admin（多语句）与 user（单语句）两个连接池。
func openSQLConnections(cfg config.Config) (*sql.DB, *sql.DB, error) {
	base := mysql.NewConfig()
	base.User = cfg.JudgeMysqlUser
	base.Passwd = cfg.JudgeMysqlPassword
	base.Net = "tcp"
	base.Addr = cfg.JudgeMysqlHost + ":" + cfg.JudgeMysqlPort
	base.Timeout = 5 * time.Second
	base.Params = map[string]string{"charset": "utf8mb4"}

	maxOpen := cfg.WorkerConcurrency + 2
	if maxOpen < 4 {
		maxOpen = 4
	}

	// admin 连接允许多条语句，用于 DDL/seed/reference/verify 脚本。
	base.MultiStatements = true
	admin, err := sql.Open("mysql", base.FormatDSN())
	if err != nil {
		return nil, nil, err
	}
	admin.SetMaxOpenConns(maxOpen)
	admin.SetMaxIdleConns(maxOpen)
	admin.SetConnMaxLifetime(10 * time.Minute)

	// user 连接强制单条语句，用户提交多条语句会被驱动拒绝。
	base.MultiStatements = false
	user, err := sql.Open("mysql", base.FormatDSN())
	if err != nil {
		_ = admin.Close()
		return nil, nil, err
	}
	user.SetMaxOpenConns(maxOpen)
	user.SetMaxIdleConns(maxOpen)
	user.SetConnMaxLifetime(10 * time.Minute)

	if err := admin.Ping(); err != nil {
		_ = admin.Close()
		_ = user.Close()
		return nil, nil, err
	}
	if err := user.Ping(); err != nil {
		_ = admin.Close()
		_ = user.Close()
		return nil, nil, err
	}
	return admin, user, nil
}

func extractSQL(task model.Task) string {
	if code, ok := task.CodeContent["answer.sql"]; ok && strings.TrimSpace(code) != "" {
		return code
	}
	for _, code := range task.CodeContent {
		if strings.TrimSpace(code) != "" {
			return code
		}
	}
	return ""
}

func truncateResultSet(rs compare.ResultSet) string {
	if len(rs.Rows) > maxResultRows {
		rs.Rows = rs.Rows[:maxResultRows]
	}
	data, err := json.Marshal(rs)
	if err != nil {
		return ""
	}
	return string(data)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

// 以下为 SQL 专用状态回写助手，操作独立 sqlQ。
func (j *Judge) publishSQL(ctx context.Context, sqlQ *queue.Queue, submissionID int64, status string, total, finished int) {
	vo := model.SubmissionVO{
		SubmissionID: submissionID,
		Status:       status,
		Progress:     &model.Progress{TotalCases: total, FinishedCases: finished},
	}
	j.writeSQLStatus(ctx, sqlQ, submissionID, vo)
}

func (j *Judge) finishSQLSystemError(ctx context.Context, sqlQ *queue.Queue, submissionID int64) {
	result := model.Result{Score: intPtr(0), TestCasesReport: []model.CaseResult{}}
	j.finishSQLTerminal(ctx, sqlQ, submissionID, sandbox.StatusSystemError, result)
}

func (j *Judge) finishSQLTerminal(ctx context.Context, sqlQ *queue.Queue, submissionID int64, status string, result model.Result) {
	vo := model.SubmissionVO{SubmissionID: submissionID, Status: status, Result: &result}
	j.writeSQLStatus(ctx, sqlQ, submissionID, vo)
	_ = sqlQ.PushResult(ctx, submissionID)
}

func (j *Judge) writeSQLStatus(ctx context.Context, sqlQ *queue.Queue, submissionID int64, vo model.SubmissionVO) {
	data, err := json.Marshal(vo)
	if err != nil {
		return
	}
	_ = sqlQ.PublishStatus(ctx, submissionID, string(data))
}
