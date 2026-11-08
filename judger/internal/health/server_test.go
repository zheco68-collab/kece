package health

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthzEndpointReadOnlyAndStructured(t *testing.T) {
	h := Handler(func() Status {
		return Status{
			ProtocolVersion: 1,
			NodeID:          "12",
			Pool:            "python",
			RuntimeSpec:     "python-v1",
			RuntimeVersion:  "3.12",
			State:           "ENABLED",
			Healthy:         true,
			ActiveSlots:     1,
			LocalSlots:      2,
			MaxSlots:        2,
		}
	})

	req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /healthz status = %d, want 200", rec.Code)
	}
	var got Status
	if err := json.NewDecoder(rec.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.ProtocolVersion != 1 || got.Pool != "python" || got.NodeID != "12" || !got.Healthy {
		t.Fatalf("unexpected health status: %+v", got)
	}

	// 非 GET 方法必须拒绝
	postReq := httptest.NewRequest(http.MethodPost, "/healthz", nil)
	postRec := httptest.NewRecorder()
	h.ServeHTTP(postRec, postReq)
	if postRec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST /healthz status = %d, want 405", postRec.Code)
	}

	// 其他任意路径必须 404，不暴露任何评测或执行入口
	otherReq := httptest.NewRequest(http.MethodGet, "/exec", nil)
	otherRec := httptest.NewRecorder()
	h.ServeHTTP(otherRec, otherReq)
	if otherRec.Code != http.StatusNotFound {
		t.Fatalf("GET /exec status = %d, want 404", otherRec.Code)
	}
}
