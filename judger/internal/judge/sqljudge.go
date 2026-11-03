package judge

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
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
func (j *Judge) ProcessSQL(ctx context.Context, sqlQ *queue.Queue, task model.Task) error {
	sqlText := extractSQL(task)
	if sqlText == "" {
		return j.finishSQLSystemError(ctx, sqlQ, task.SubmissionID)
	}
	adminPool, err := j.getSQLAdminPool()
	if err != nil {
		log.Printf("sqljudge: getSQLAdminPool failed: %v", err)
		return j.finishSQLSystemError(ctx, sqlQ, task.SubmissionID)
	}
	adminConn, err := adminPool.Conn(ctx)
	if err != nil {
		log.Printf("sqljudge: acquire adminConn failed: %v", err)
		return j.finishSQLSystemError(ctx, sqlQ, task.SubmissionID)
	}
	defer adminConn.Close()

	j.publishSQL(ctx, sqlQ, task.SubmissionID, statusJudging, len(task.TestCases), 0)

	report := make([]model.CaseResult, 0, len(task.TestCases))
	statuses := make([]string, 0, len(task.TestCases))
	start := time.Now()
	for i, tc := range task.TestCases {
		caseStart := time.Now()
		cr, err := j.runSQLCase(ctx, adminConn, task, tc, sqlText)
		if err != nil {
			log.Printf("sqljudge: runSQLCase failed on case %d: %v", i+1, err)
			return j.finishSQLSystemError(ctx, sqlQ, task.SubmissionID)
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
	return j.finishSQLTerminal(ctx, sqlQ, task.SubmissionID, overall, result)
}

// runSQLCase 运行单个测试点：建库→DDL/seed→计算期望→运行用户 SQL→计算实际→比对。
// 返回错误表示判题机自身或教师脚本出错（整题 SYSTEM_ERROR），用户 SQL 错误仅体现在 CaseResult。
func (j *Judge) runSQLCase(
	ctx context.Context,
	adminConn sqlExecutor,
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

	identity, err := newSQLCaseIdentity()
	if err != nil {
		return model.CaseResult{}, err
	}
	dbName := identity.database
	// Schedule cleanup before creation: a timeout can lose the reply to a successful DDL.
	defer func() {
		cleanupCtx, cancelCleanup := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		defer cancelCleanup()
		// An expired case can invalidate its dedicated admin connection. Use the
		// pool to obtain a live connection for cleanup.
		pool, err := j.getSQLAdminPool()
		if err == nil {
			if err := dropDatabase(cleanupCtx, pool, dbName); err != nil {
				log.Printf("sqljudge: cleanup database %s: %v", dbName, err)
			}
		}
	}()
	if err := createDatabase(caseCtx, adminConn, dbName); err != nil {
		return model.CaseResult{}, fmt.Errorf("创建临时库失败: %w", err)
	}

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

	dml := strings.TrimSpace(task.VerifySql) != ""
	if dml {
		if err := resetDatabase(caseCtx, adminConn, dbName, task.SchemaSql, tc.SeedSql); err != nil {
			return model.CaseResult{}, fmt.Errorf("重置临时库失败: %w", err)
		}
	}
	defer func() {
		cleanupCtx, cancelCleanup := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		defer cancelCleanup()
		pool, err := j.getSQLAdminPool()
		if err == nil {
			if _, err := pool.ExecContext(cleanupCtx, "DROP USER IF EXISTS "+identity.account()); err != nil {
				log.Printf("sqljudge: cleanup account %s: %v", identity.user, err)
			}
		}
	}()
	userDB, err := openSQLCaseUser(caseCtx, adminConn, j.cfg, identity, dml)
	if err != nil {
		return model.CaseResult{}, fmt.Errorf("建立隔离作答连接失败: %w", err)
	}
	defer userDB.Close()
	userConn, err := userDB.Conn(caseCtx)
	if err != nil {
		return model.CaseResult{}, fmt.Errorf("获取隔离作答连接失败: %w", err)
	}
	defer userConn.Close()

	cr := model.CaseResult{CaseID: tc.CaseID}
	actual, userErr := actualResultSet(caseCtx, adminConn, userConn, dbName, task, sqlText)
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

	ok, _ := compare.EqualResultSetsContext(caseCtx, expected, actual, task.OrderSensitive)
	if caseCtx.Err() != nil {
		cr.Status = sandbox.StatusTimeLimitExceeded
	} else if ok {
		cr.Status = sandbox.StatusAccepted
	} else {
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

// actualResultSet 在已准备好的库中运行用户 SQL；DML 题再用校验 SQL 读取结果。
func actualResultSet(
	ctx context.Context,
	adminConn, userConn sqlExecutor,
	dbName string,
	task model.Task,
	sqlText string,
) (compare.ResultSet, error) {
	if strings.TrimSpace(task.VerifySql) != "" {
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

// getSQLAdminPool 只为判题机自身和教师脚本建立管理连接池。
func (j *Judge) getSQLAdminPool() (*sql.DB, error) {
	j.sqlMu.Lock()
	defer j.sqlMu.Unlock()
	if j.adminPool != nil {
		return j.adminPool, nil
	}
	cfg := mysqlConfig(j.cfg)
	cfg.User = j.cfg.JudgeMysqlUser
	cfg.Passwd = j.cfg.JudgeMysqlPassword
	cfg.MultiStatements = true
	db, err := sql.Open("mysql", cfg.FormatDSN())
	if err != nil {
		return nil, err
	}
	maxOpen := max(4, j.cfg.WorkerConcurrency+2)
	db.SetMaxOpenConns(maxOpen)
	db.SetMaxIdleConns(maxOpen)
	db.SetConnMaxLifetime(10 * time.Minute)
	j.adminPool = db
	return db, nil
}

type sqlCaseIdentity struct {
	database string
	user     string
	password string
}

func newSQLCaseIdentity() (sqlCaseIdentity, error) {
	var random [36]byte
	if _, err := rand.Read(random[:]); err != nil {
		return sqlCaseIdentity{}, err
	}
	suffix := hex.EncodeToString(random[:12])
	return sqlCaseIdentity{
		// A GRANT database name must not contain SQL wildcard characters.
		database: "kecejudge" + suffix,
		user:     "kc" + suffix,
		password: "Kc9!" + hex.EncodeToString(random[12:]),
	}, nil
}

func (id sqlCaseIdentity) account() string { return "'" + id.user + "'@'%'" }

func (id sqlCaseIdentity) grant(dml bool) string {
	privileges := "SELECT"
	if dml {
		privileges += ", INSERT, UPDATE, DELETE"
	}
	return "GRANT " + privileges + " ON `" + id.database + "`.* TO " + id.account()
}

func mysqlConfig(cfg config.Config) *mysql.Config {
	base := mysql.NewConfig()
	base.Net = "tcp"
	base.Addr = cfg.JudgeMysqlHost + ":" + cfg.JudgeMysqlPort
	base.Timeout = 5 * time.Second
	base.Params = map[string]string{"charset": "utf8mb4"}
	return base
}

func openSQLCaseUser(ctx context.Context, admin sqlExecutor, cfg config.Config, id sqlCaseIdentity, dml bool) (*sql.DB, error) {
	// All interpolated values here are generated by newSQLCaseIdentity.
	// Do not include MySQL's error for CREATE USER in logs: it may contain the password.
	if _, err := admin.ExecContext(ctx, "CREATE USER "+id.account()+" IDENTIFIED BY '"+id.password+"'"); err != nil {
		return nil, errors.New("创建临时作答账号失败")
	}
	if _, err := admin.ExecContext(ctx, id.grant(dml)); err != nil {
		return nil, fmt.Errorf("授予临时作答权限失败: %w", err)
	}
	user := mysqlConfig(cfg)
	user.User = id.user
	user.Passwd = id.password
	user.DBName = id.database
	db, err := sql.Open("mysql", user.FormatDSN())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(0)
	return db, nil
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
		if len(rs.Nulls) > maxResultRows {
			rs.Nulls = rs.Nulls[:maxResultRows]
		}
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

func (j *Judge) finishSQLSystemError(ctx context.Context, sqlQ *queue.Queue, submissionID int64) error {
	result := model.Result{Score: intPtr(0), TestCasesReport: []model.CaseResult{}}
	return j.finishSQLTerminal(ctx, sqlQ, submissionID, sandbox.StatusSystemError, result)
}

func (j *Judge) finishSQLTerminal(ctx context.Context, sqlQ *queue.Queue, submissionID int64, status string, result model.Result) error {
	vo := model.SubmissionVO{SubmissionID: submissionID, Status: status, Result: &result}
	return publishTerminal(ctx, sqlQ, vo)
}

func (j *Judge) writeSQLStatus(ctx context.Context, sqlQ *queue.Queue, submissionID int64, vo model.SubmissionVO) {
	data, err := json.Marshal(vo)
	if err != nil {
		return
	}
	_ = sqlQ.PublishStatus(ctx, submissionID, string(data))
}
