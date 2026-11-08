package judge

// CodeTask contains the inputs needed by the sandbox, independent of a queue.
type CodeTask struct {
	Language         string
	CodeContent      map[string]string
	TimeLimitMs      int
	MemoryLimitMb    int
	RequireCaseFiles bool
	TestCases        []TestCase
}

// SQLTask contains the scripts and limits needed by the SQL executor.
type SQLTask struct {
	Answer         string
	TimeLimitMs    int
	SchemaSql      string
	ReferenceSql   string
	VerifySql      string
	OrderSensitive bool
	TestCases      []TestCase
}

type TestCase struct {
	CaseID         int64
	ScoreWeight    int
	Input          string
	ExpectedOutput string
	InputPath      string
	OutputPath     string
	SeedSql        string
}

// ProgressSink receives progress synchronously. A nil sink disables reporting.
type ProgressSink func(status string, total, finished int)

func (report ProgressSink) update(status string, total, finished int) {
	if report != nil {
		report(status, total, finished)
	}
}

// Verdict holds a completed judgment. Infrastructure errors are returned separately.
type Verdict struct {
	Status          string
	Score           *int
	TimeUsedMs      *int
	MemoryUsedKb    *int
	CompileErrorLog *string
	TestCasesReport []CaseResult
}

type CaseResult struct {
	CaseID         int64
	Status         string
	TimeMs         *int
	MemoryKb       *int
	ExpectedOutput *string
	ActualOutput   *string
}
