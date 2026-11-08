package judge

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"
)

func TestSQLCaseIdentityTimestampAndNoWildcards(t *testing.T) {
	fixed := time.Unix(1794083400, 0)
	id, err := newSQLCaseIdentityAt(fixed)
	if err != nil {
		t.Fatal(err)
	}
	if strings.ContainsAny(id.database, "_%") || strings.ContainsAny(id.user, "_%") {
		t.Fatalf("identity must not contain SQL wildcards: db=%q user=%q", id.database, id.user)
	}
	if len(id.user) > 32 || len(id.database) > 64 {
		t.Fatalf("identity exceeds MySQL identifier limits: db=%d user=%d", len(id.database), len(id.user))
	}
	dbTime, ok := parseManagedTimestamp(id.database, "kecejudge")
	if !ok || !dbTime.Equal(fixed) {
		t.Fatalf("parseManagedTimestamp(db) = (%v, %v), want (%v, true)", dbTime, ok, fixed)
	}
	userTime, ok := parseManagedTimestamp(id.user, "kc")
	if !ok || !userTime.Equal(fixed) {
		t.Fatalf("parseManagedTimestamp(user) = (%v, %v), want (%v, true)", userTime, ok, fixed)
	}
	if _, ok := parseManagedTimestamp("kece", "kecejudge"); ok {
		t.Fatal("business database name must never match managed timestamp")
	}
	if _, ok := parseManagedTimestamp("kecejudge1234567890_bad_chars!!", "kecejudge"); ok {
		t.Fatal("invalid characters must be rejected")
	}
}

func TestSQLCaseUserIsolation(t *testing.T) {
	port := os.Getenv("KECE_TEST_MYSQL_PORT")
	if port == "" {
		t.Skip("set KECE_TEST_MYSQL_PORT for a disposable MySQL instance")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cfg := SQLConfig{
		Host: "127.0.0.1", Port: port,
		User: "root", Password: os.Getenv("KECE_TEST_MYSQL_PASSWORD"),
	}
	j := NewSQL(cfg)
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
