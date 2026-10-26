package judge

import (
	"testing"

	"github.com/zheco68-collab/KECE/judger/internal/model"
)

func TestAggregateOverall(t *testing.T) {
	tests := []struct {
		name     string
		statuses []string
		want     string
	}{
		{"all accepted", []string{"ACCEPTED", "ACCEPTED"}, "ACCEPTED"},
		{"empty", nil, "ACCEPTED"},
		{"first wa", []string{"ACCEPTED", "WRONG_ANSWER", "ACCEPTED"}, "WRONG_ANSWER"},
		{"tle before re", []string{"TIME_LIMIT_EXCEEDED", "RUNTIME_ERROR"}, "TIME_LIMIT_EXCEEDED"},
		{"re only", []string{"ACCEPTED", "RUNTIME_ERROR"}, "RUNTIME_ERROR"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := AggregateOverall(tt.statuses); got != tt.want {
				t.Fatalf("AggregateOverall(%v) = %q, want %q", tt.statuses, got, tt.want)
			}
		})
	}
}

func TestScoreFrom(t *testing.T) {
	tests := []struct {
		name     string
		statuses []string
		want     int
	}{
		{"all accepted", []string{"ACCEPTED", "ACCEPTED"}, 100},
		{"empty", nil, 0},
		{"half", []string{"ACCEPTED", "WRONG_ANSWER"}, 50},
		{"one of three", []string{"ACCEPTED", "WRONG_ANSWER", "RUNTIME_ERROR"}, 33},
		{"three of four", []string{"ACCEPTED", "ACCEPTED", "ACCEPTED", "WRONG_ANSWER"}, 75},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := scoreFrom(tt.statuses); got != tt.want {
				t.Fatalf("scoreFrom(%v) = %d, want %d", tt.statuses, got, tt.want)
			}
		})
	}

	cases := []model.TestCase{
		{CaseID: 1, ScoreWeight: 20},
		{CaseID: 2, ScoreWeight: 80},
	}
	if got := scoreFromCases(cases, []string{"WRONG_ANSWER", "ACCEPTED"}); got != 80 {
		t.Fatalf("scoreFromCases weighted = %d, want 80", got)
	}
}

func TestFilenameFor(t *testing.T) {
	tests := map[string]string{
		"cpp":    "main.cpp",
		"java":   "Main.java",
		"python": "main.py",
		"arkts":  "main.ets",
		"go":     "main.txt",
	}
	for lang, want := range tests {
		if got := filenameFor(lang); got != want {
			t.Fatalf("filenameFor(%q) = %q, want %q", lang, got, want)
		}
	}
}

func TestExtractCode(t *testing.T) {
	task := model.Task{CodeContent: map[string]string{"main.py": "print(1)", "main.cpp": "int main(){}"}}
	if got := extractCode(task, "main.py"); got != "print(1)" {
		t.Fatalf("extractCode = %q", got)
	}
	if got := extractCode(task, "missing.txt"); got == "" {
		t.Fatal("expected fallback to any non-empty code")
	}

	taskArkts := model.Task{CodeContent: map[string]string{"main.ts": "console.log(123);"}}
	if got := extractCode(taskArkts, "main.ets"); got != "console.log(123);" {
		t.Fatalf("extractCode(main.ets) fallback to main.ts = %q", got)
	}
}
