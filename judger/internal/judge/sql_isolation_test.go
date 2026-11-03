package judge

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/zheco68-collab/KECE/judger/internal/config"
)

func TestSQLCaseUserIsolation(t *testing.T) {
	port := os.Getenv("KECE_TEST_MYSQL_PORT")
	if port == "" {
		t.Skip("set KECE_TEST_MYSQL_PORT for a disposable MySQL instance")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cfg := config.Config{
		JudgeMysqlHost: "127.0.0.1", JudgeMysqlPort: port,
		JudgeMysqlUser: "root", JudgeMysqlPassword: os.Getenv("KECE_TEST_MYSQL_PASSWORD"),
	}
	j := New(cfg, nil, nil)
	t.Cleanup(j.Close)
	admin, err := j.getSQLAdminPool()
	if err != nil {
		t.Fatal(err)
	}
	setup := func(dml bool) (sqlCaseIdentity, sqlExecutor) {
		t.Helper()
		id, err := newSQLCaseIdentity()
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			_, _ = admin.Exec("DROP USER IF EXISTS " + id.account())
			_ = dropDatabase(context.Background(), admin, id.database)
		})
		if err := createDatabase(ctx, admin, id.database); err != nil {
			t.Fatal(err)
		}
		if _, err := admin.ExecContext(ctx, "CREATE TABLE "+id.database+".answers (value INT)"); err != nil {
			t.Fatal(err)
		}
		user, err := openSQLCaseUser(ctx, admin, cfg, id, dml)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = user.Close() })
		return id, user
	}
	readID, reader := setup(false)
	writeID, writer := setup(true)
	rows, err := reader.QueryContext(ctx, "SELECT value FROM "+readID.database+".answers")
	if err != nil {
		t.Fatal(err)
	}
	_ = rows.Close()
	if _, err := writer.ExecContext(ctx, "INSERT INTO "+writeID.database+".answers VALUES (2)"); err != nil {
		t.Fatalf("DML user should be allowed to modify its own database: %v", err)
	}
	for _, check := range []struct {
		user  sqlExecutor
		query string
	}{
		{reader, "INSERT INTO " + readID.database + ".answers VALUES (2)"},
		{writer, "INSERT INTO " + readID.database + ".answers VALUES (3)"},
		{writer, "SELECT * FROM mysql.user"},
		{writer, "DROP DATABASE " + readID.database},
	} {
		if _, err := check.user.ExecContext(ctx, check.query); err == nil {
			t.Errorf("unexpected permission to execute %q", check.query)
		}
	}
}
