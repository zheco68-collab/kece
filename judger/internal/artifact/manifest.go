package artifact

import (
	"context"
	"fmt"
	"os"

	"github.com/zheco68-collab/KECE/judger/internal/judge"
)

type Manifest struct {
	Version        int        `json:"version"`
	SubmissionType string     `json:"submissionType"`
	TimeLimitMs    int        `json:"timeLimitMs"`
	MemoryLimitMb  int        `json:"memoryLimitMb"`
	SQL            *SQLSpec   `json:"sql"`
	Cases          []CaseSpec `json:"cases"`
}

type SQLSpec struct {
	Dialect        string `json:"dialect"`
	Type           string `json:"type"`
	Schema         *Ref   `json:"schema"`
	Reference      *Ref   `json:"reference"`
	Verify         *Ref   `json:"verify"`
	OrderSensitive bool   `json:"orderSensitive"`
}

type CaseSpec struct {
	CaseID      int64 `json:"caseId"`
	CaseNum     int   `json:"caseNum,omitempty"`
	ScoreWeight int   `json:"scoreWeight"`
	Sample      bool  `json:"sample,omitempty"`
	Input       *Ref  `json:"input"`
	Output      *Ref  `json:"output"`
	Seed        *Ref  `json:"seed"`
}

type SourceSnapshot struct {
	QuestionID  int64             `json:"questionId,omitempty"`
	Language    string            `json:"language"`
	CodeContent map[string]string `json:"codeContent"`
}

type Prepared struct {
	Code    *judge.CodeTask
	SQL     *judge.SQLTask
	handles []*Handle
}

func (p *Prepared) Close() {
	for _, handle := range p.handles {
		handle.Close()
	}
}

// Prepare resolves a task without access to the backend's testcase directory.
// The caller must keep the returned inputs open until execution finishes.
func (c *Cache) Prepare(ctx context.Context, manifest Manifest, source SourceSnapshot, fetch Fetch) (*Prepared, error) {
	if manifest.Version != 1 || manifest.TimeLimitMs <= 0 || len(manifest.Cases) == 0 {
		return nil, fmt.Errorf("unsupported or empty judging manifest")
	}
	p := &Prepared{}
	if err := c.prepare(ctx, p, manifest, source, fetch); err != nil {
		p.Close()
		return nil, err
	}
	return p, nil
}

func (c *Cache) prepare(ctx context.Context, p *Prepared, manifest Manifest, source SourceSnapshot, fetch Fetch) error {
	load := func(ref *Ref) (string, error) {
		if ref == nil {
			return "", fmt.Errorf("required artifact is missing")
		}
		h, err := c.Acquire(ctx, *ref, fetch)
		if err != nil {
			return "", err
		}
		p.handles = append(p.handles, h)
		return h.Path, nil
	}
	if manifest.SubmissionType == "ALGO" {
		switch source.Language {
		case "cpp", "java", "python", "arkts":
		default:
			return fmt.Errorf("unsupported code runtime")
		}
		if manifest.SQL != nil || manifest.MemoryLimitMb <= 0 {
			return fmt.Errorf("invalid code manifest")
		}
		task := &judge.CodeTask{
			Language: source.Language, CodeContent: source.CodeContent,
			TimeLimitMs: manifest.TimeLimitMs, MemoryLimitMb: manifest.MemoryLimitMb,
			RequireCaseFiles: true,
		}
		for _, spec := range manifest.Cases {
			input, err := load(spec.Input)
			if err != nil {
				return fmt.Errorf("case %d input: %w", spec.CaseID, err)
			}
			output, err := load(spec.Output)
			if err != nil {
				return fmt.Errorf("case %d output: %w", spec.CaseID, err)
			}
			task.TestCases = append(task.TestCases, judge.TestCase{
				CaseID: spec.CaseID, ScoreWeight: spec.ScoreWeight, InputPath: input, OutputPath: output,
			})
		}
		p.Code = task
		return nil
	}

	spec := manifest.SQL
	if manifest.SubmissionType != "DATABASE" || source.Language != "mysql" || spec == nil ||
		spec.Dialect != "MYSQL" || spec.Reference == nil {
		return fmt.Errorf("invalid SQL manifest")
	}
	read := func(ref *Ref) (string, error) {
		if ref == nil {
			return "", nil
		}
		path, err := load(ref)
		if err != nil {
			return "", err
		}
		body, err := os.ReadFile(path)
		return string(body), err
	}
	task := &judge.SQLTask{
		Answer: source.CodeContent["answer.sql"], TimeLimitMs: manifest.TimeLimitMs,
		OrderSensitive: spec.OrderSensitive,
	}
	var err error
	if task.SchemaSql, err = read(spec.Schema); err != nil {
		return err
	}
	if task.ReferenceSql, err = read(spec.Reference); err != nil {
		return err
	}
	if task.VerifySql, err = read(spec.Verify); err != nil {
		return err
	}
	for _, item := range manifest.Cases {
		seed, err := read(item.Seed)
		if err != nil {
			return fmt.Errorf("case %d seed: %w", item.CaseID, err)
		}
		task.TestCases = append(task.TestCases, judge.TestCase{
			CaseID: item.CaseID, ScoreWeight: item.ScoreWeight, SeedSql: seed,
		})
	}
	p.SQL = task
	return nil
}
