package control

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/zheco68-collab/KECE/judger/internal/artifact"
)

// Standard error types for control plane communication.
var (
	ErrUnauthorized = fmt.Errorf("control plane: unauthorized")
	ErrConflict     = fmt.Errorf("control plane: conflict or stale session")
	ErrRateLimited  = fmt.Errorf("control plane: rate limited")
	ErrUnavailable  = fmt.Errorf("control plane: service unavailable")
)

type ProtocolError struct {
	StatusCode int
	Code       string
}

func (e *ProtocolError) Error() string {
	return fmt.Sprintf("control protocol error: HTTP %d (code=%s)", e.StatusCode, e.Code)
}

type Client struct {
	baseURL    string
	httpClient *http.Client
	mu         sync.RWMutex
	credential string
	nodeID     string
	epoch      int64
}

func NewClient(controlURL string, client *http.Client) (*Client, error) {
	parsed, err := url.Parse(controlURL)
	if err != nil || parsed.Hostname() == "" || parsed.User != nil || parsed.ForceQuery ||
		parsed.RawQuery != "" || parsed.Fragment != "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return nil, errors.New("invalid control URL")
	}
	if client == nil {
		client = &http.Client{Timeout: 30 * time.Second}
	}
	directClient := *client
	directClient.CheckRedirect = func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}
	return &Client{
		baseURL:    strings.TrimRight(controlURL, "/"),
		httpClient: &directClient,
	}, nil
}

func (c *Client) SetCredentials(nodeID, credential string, epoch int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.nodeID = nodeID
	c.credential = credential
	c.epoch = epoch
}

func (c *Client) NodeID() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.nodeID
}

func (c *Client) Epoch() int64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.epoch
}

func (c *Client) Credential() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.credential
}

func (c *Client) HTTPClient() *http.Client {
	return c.httpClient
}

func (c *Client) BaseURL() string {
	return c.baseURL
}

// DTOs

type JoinRequest struct {
	EnrollmentCode  string `json:"enrollmentCode"`
	BootID          string `json:"bootId"`
	ProtocolVersion int    `json:"protocolVersion"`
}

type Joined struct {
	NodeID          string `json:"nodeId"`
	Credential      string `json:"credential"`
	SessionEpoch    int64  `json:"sessionEpoch"`
	ProtocolVersion int    `json:"protocolVersion"`
	Pool            string `json:"pool"`
	RuntimeSpec     string `json:"runtimeSpec"`
}

type SessionRequest struct {
	BootID          string `json:"bootId"`
	ProtocolVersion int    `json:"protocolVersion"`
}

type Session struct {
	NodeID          string `json:"nodeId"`
	SessionEpoch    int64  `json:"sessionEpoch"`
	ProtocolVersion int    `json:"protocolVersion"`
	Pool            string `json:"pool"`
	RuntimeSpec     string `json:"runtimeSpec"`
}

type CheckReport struct {
	CheckID string `json:"checkId"`
	Passed  bool   `json:"passed"`
	Detail  string `json:"detail,omitempty"`
}

type HeartbeatRequest struct {
	RuntimeSpec    string       `json:"runtimeSpec"`
	RuntimeVersion string       `json:"runtimeVersion"`
	LocalSlots     int          `json:"localSlots"`
	Healthy        bool         `json:"healthy"`
	CheckResult    *CheckReport `json:"checkResult,omitempty"`
}

type CheckDirective struct {
	CheckID     string `json:"checkId"`
	RemainingMs int64  `json:"remainingMs"`
}

type HeartbeatResponse struct {
	State    string          `json:"state"`
	Revision int64           `json:"revision"`
	MaxSlots int             `json:"maxSlots"`
	Check    *CheckDirective `json:"check,omitempty"`
}

type ClaimRequest struct {
	Slots  int `json:"slots"`
	WaitMs int `json:"waitMs"`
}

type ClaimedJob struct {
	JobID            string                  `json:"jobId"`
	SubmissionType   string                  `json:"submissionType"`
	SubmissionID     string                  `json:"submissionId"`
	Generation       int                     `json:"generation"`
	Pool             string                  `json:"pool"`
	RuntimeSpec      string                  `json:"runtimeSpec"`
	LeaseEpoch       int64                   `json:"leaseEpoch"`
	LeaseRemainingMs int64                   `json:"leaseRemainingMs"`
	ManifestID       string                  `json:"manifestId"`
	Manifest         artifact.Manifest       `json:"manifest"`
	Source           artifact.SourceSnapshot `json:"source"`
	ArtifactToken    string                  `json:"artifactToken"`
}

type ClaimBatch struct {
	ProtocolVersion int          `json:"protocolVersion"`
	Jobs            []ClaimedJob `json:"jobs"`
}

type Progress struct {
	JobID         string `json:"jobId"`
	LeaseEpoch    int64  `json:"leaseEpoch"`
	Sequence      int64  `json:"sequence"`
	Phase         string `json:"phase"`
	FinishedCases int    `json:"finishedCases"`
}

type RenewRequest struct {
	Jobs []Progress `json:"jobs"`
}

type Renewed struct {
	JobID            string `json:"jobId"`
	Status           string `json:"status"`
	LeaseRemainingMs int64  `json:"leaseRemainingMs"`
}

type RenewBatch struct {
	Jobs []Renewed `json:"jobs"`
}

type CaseResultVO struct {
	CaseID         int64   `json:"caseId"`
	Status         string  `json:"status"`
	TimeMs         *int    `json:"timeMs,omitempty"`
	MemoryKb       *int    `json:"memoryKb,omitempty"`
	ExpectedOutput *string `json:"expectedOutput,omitempty"`
	ActualOutput   *string `json:"actualOutput,omitempty"`
}

type SubmissionResultVO struct {
	Score           *int           `json:"score,omitempty"`
	TimeUsedMs      *int           `json:"timeUsedMs,omitempty"`
	MemoryUsedKb    *int           `json:"memoryUsedKb,omitempty"`
	CompileErrorLog *string        `json:"compileErrorLog,omitempty"`
	TestCasesReport []CaseResultVO `json:"testCasesReport"`
}

type ResultRequest struct {
	LeaseEpoch int64               `json:"leaseEpoch"`
	Status     string              `json:"status,omitempty"`
	Result     *SubmissionResultVO `json:"result,omitempty"`
	ErrorCode  *string             `json:"errorCode,omitempty"`
}

type Receipt struct {
	Status string `json:"status"`
}

func (c *Client) Join(ctx context.Context, enrollmentCode, bootID string) (*Joined, error) {
	reqBody := JoinRequest{
		EnrollmentCode:  enrollmentCode,
		BootID:          bootID,
		ProtocolVersion: 1,
	}
	var out Joined
	if err := c.post(ctx, "/internal/judgers/join", reqBody, &out, false); err != nil {
		return nil, err
	}
	c.SetCredentials(out.NodeID, out.Credential, out.SessionEpoch)
	return &out, nil
}

func (c *Client) Session(ctx context.Context, bootID string) (*Session, error) {
	reqBody := SessionRequest{
		BootID:          bootID,
		ProtocolVersion: 1,
	}
	var out Session
	if err := c.post(ctx, "/internal/judgers/session", reqBody, &out, true); err != nil {
		return nil, err
	}
	c.mu.Lock()
	c.epoch = out.SessionEpoch
	c.mu.Unlock()
	return &out, nil
}

func (c *Client) Heartbeat(ctx context.Context, req HeartbeatRequest) (*HeartbeatResponse, error) {
	var out HeartbeatResponse
	if err := c.post(ctx, "/internal/judgers/heartbeat", req, &out, true); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) Claim(ctx context.Context, slots, waitMs int) (*ClaimBatch, error) {
	reqBody := ClaimRequest{
		Slots:  slots,
		WaitMs: waitMs,
	}
	var out ClaimBatch
	if err := c.post(ctx, "/internal/judgers/jobs/claim", reqBody, &out, true); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) Renew(ctx context.Context, progress []Progress) ([]Renewed, error) {
	reqBody := RenewRequest{Jobs: progress}
	var out RenewBatch
	if err := c.post(ctx, "/internal/judgers/jobs/renew", reqBody, &out, true); err != nil {
		return nil, err
	}
	return out.Jobs, nil
}

func (c *Client) Result(ctx context.Context, jobID string, req ResultRequest) (*Receipt, error) {
	var out Receipt
	endpoint := fmt.Sprintf("/internal/judgers/jobs/%s/result", jobID)
	if err := c.post(ctx, endpoint, req, &out, true); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) post(ctx context.Context, path string, body any, target any, auth bool) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("marshal request: %w", err)
	}

	urlStr := c.baseURL + path
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, urlStr, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}

	httpReq.Header.Set("Content-Type", "application/json")
	if auth {
		c.mu.RLock()
		cred := c.credential
		epoch := c.epoch
		c.mu.RUnlock()

		if cred != "" {
			httpReq.Header.Set("Authorization", "Bearer "+cred)
		}
		if epoch > 0 {
			httpReq.Header.Set("X-Judge-Session-Epoch", fmt.Sprintf("%d", epoch))
		}
	}

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return fmt.Errorf("http post %s: %w", path, err)
	}
	defer resp.Body.Close()

	respBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("read response body: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		var protoErr struct {
			Code string `json:"code"`
		}
		_ = json.Unmarshal(respBytes, &protoErr)
		pErr := &ProtocolError{StatusCode: resp.StatusCode, Code: protoErr.Code}

		switch resp.StatusCode {
		case http.StatusUnauthorized, http.StatusForbidden:
			return fmt.Errorf("%w: %v", ErrUnauthorized, pErr)
		case http.StatusConflict:
			return fmt.Errorf("%w: %w", ErrConflict, pErr)
		case http.StatusTooManyRequests:
			return fmt.Errorf("%w: %v", ErrRateLimited, pErr)
		case http.StatusServiceUnavailable, http.StatusBadGateway, http.StatusGatewayTimeout:
			return fmt.Errorf("%w: %v", ErrUnavailable, pErr)
		default:
			return pErr
		}
	}

	if target != nil && len(respBytes) > 0 {
		if err := json.Unmarshal(respBytes, target); err != nil {
			return fmt.Errorf("unmarshal response (%s): %w", string(respBytes), err)
		}
	}
	return nil
}
