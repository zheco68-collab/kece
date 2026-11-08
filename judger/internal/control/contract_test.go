package control

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func contractDir(t *testing.T) string {
	t.Helper()
	return filepath.Join("..", "..", "..", "docs", "contracts")
}

func decodeStrict[T any](t *testing.T, raw json.RawMessage) T {
	t.Helper()
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	var out T
	if err := dec.Decode(&out); err != nil {
		t.Fatalf("strict decode %T failed: %v\npayload: %s", out, err, string(raw))
	}
	return out
}

func loadFixturePair(t *testing.T, filename string) (json.RawMessage, json.RawMessage) {
	t.Helper()
	path := filepath.Join(contractDir(t), "samples", filename)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read contract fixture %s: %v", path, err)
	}
	var pair struct {
		Request  json.RawMessage `json:"request"`
		Response json.RawMessage `json:"response"`
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&pair); err != nil {
		t.Fatalf("decode fixture envelope %s: %v", filename, err)
	}
	return pair.Request, pair.Response
}

func TestOpenAPISpecAndSharedContractFixtures(t *testing.T) {
	specBytes, err := os.ReadFile(filepath.Join(contractDir(t), "judger-v1.openapi.yaml"))
	if err != nil {
		t.Fatalf("missing OpenAPI contract spec: %v", err)
	}
	spec := string(specBytes)
	for _, endpoint := range []string{
		"/internal/judgers/join:",
		"/internal/judgers/session:",
		"/internal/judgers/heartbeat:",
		"/internal/judgers/jobs/claim:",
		"/internal/judgers/jobs/renew:",
		"/internal/judgers/jobs/{id}/artifacts/{digest}:",
		"/internal/judgers/jobs/{id}/result:",
	} {
		if !strings.Contains(spec, endpoint) {
			t.Errorf("OpenAPI spec missing endpoint %s", endpoint)
		}
	}

	// 1. Join
	joinReqRaw, joinRespRaw := loadFixturePair(t, "join.json")
	joinReq := decodeStrict[JoinRequest](t, joinReqRaw)
	joinResp := decodeStrict[Joined](t, joinRespRaw)
	if joinReq.ProtocolVersion != 1 || joinResp.NodeID != "12" || joinResp.Pool != "python" {
		t.Fatalf("unexpected join fixture values: %+v / %+v", joinReq, joinResp)
	}

	// 2. Heartbeat
	hbReqRaw, hbRespRaw := loadFixturePair(t, "heartbeat.json")
	hbReq := decodeStrict[HeartbeatRequest](t, hbReqRaw)
	hbResp := decodeStrict[HeartbeatResponse](t, hbRespRaw)
	if hbReq.CheckResult == nil || hbReq.CheckResult.CheckID != "7" || hbResp.Check == nil || hbResp.State != "ENABLED" {
		t.Fatalf("unexpected heartbeat fixture values: %+v / %+v", hbReq, hbResp)
	}

	// 3. Claim (ALGO with weighted + empty-output case & SQL DML)
	_, claimAlgoRespRaw := loadFixturePair(t, "claim-algo.json")
	claimAlgo := decodeStrict[ClaimBatch](t, claimAlgoRespRaw)
	if len(claimAlgo.Jobs) != 1 || claimAlgo.Jobs[0].SubmissionID != "204" || claimAlgo.Jobs[0].Manifest.Cases[0].Output.Size != 0 {
		t.Fatalf("unexpected claim-algo fixture: %+v", claimAlgo)
	}

	_, claimSQLRespRaw := loadFixturePair(t, "claim-sql.json")
	claimSQL := decodeStrict[ClaimBatch](t, claimSQLRespRaw)
	if len(claimSQL.Jobs) != 1 || claimSQL.Jobs[0].SubmissionID != "204" || claimSQL.Jobs[0].Manifest.SQL == nil || claimSQL.Jobs[0].Manifest.SQL.Type != "DML" {
		t.Fatalf("unexpected claim-sql fixture: %+v", claimSQL)
	}

	// 4. Renew
	renewReqRaw, renewRespRaw := loadFixturePair(t, "renew.json")
	renewReq := decodeStrict[RenewRequest](t, renewReqRaw)
	renewResp := decodeStrict[RenewBatch](t, renewRespRaw)
	if len(renewReq.Jobs) != 1 || len(renewResp.Jobs) != 1 || renewResp.Jobs[0].Status != "RENEWED" {
		t.Fatalf("unexpected renew fixture: %+v / %+v", renewReq, renewResp)
	}

	// 5. Results: ACCEPTED, COMPILE_ERROR (non-null empty array), and Infrastructure Error (no status/result)
	acReqRaw, acRespRaw := loadFixturePair(t, "result-accepted.json")
	acReq := decodeStrict[ResultRequest](t, acReqRaw)
	acResp := decodeStrict[Receipt](t, acRespRaw)
	if acReq.Status != "ACCEPTED" || acReq.Result == nil || len(acReq.Result.TestCasesReport) != 2 || acResp.Status != "COMMITTED" {
		t.Fatalf("unexpected result-accepted fixture: %+v / %+v", acReq, acResp)
	}

	ceReqRaw, _ := loadFixturePair(t, "result-compile-error.json")
	ceReq := decodeStrict[ResultRequest](t, ceReqRaw)
	if ceReq.Status != "COMPILE_ERROR" || ceReq.Result == nil || ceReq.Result.TestCasesReport == nil || len(ceReq.Result.TestCasesReport) != 0 {
		t.Fatalf("COMPILE_ERROR must decode non-nil empty TestCasesReport slice: %+v", ceReq)
	}

	infraReqRaw, infraRespRaw := loadFixturePair(t, "result-infra-error.json")
	infraReq := decodeStrict[ResultRequest](t, infraReqRaw)
	infraResp := decodeStrict[Receipt](t, infraRespRaw)
	if infraReq.Status != "" || infraReq.Result != nil || infraReq.ErrorCode == nil || *infraReq.ErrorCode != "ARTIFACT_PREPARE_FAILED" || infraResp.Status != "RETRY_SCHEDULED" {
		t.Fatalf("unexpected result-infra-error fixture: %+v / %+v", infraReq, infraResp)
	}
	encodedInfra, err := json.Marshal(infraReq)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encodedInfra), `"status"`) || strings.Contains(string(encodedInfra), `"result"`) {
		t.Fatalf("marshaled infra ResultRequest must omit status and result, got: %s", string(encodedInfra))
	}
}
