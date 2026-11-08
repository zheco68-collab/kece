// Package health 提供专池节点的只读内网健康探测端点 (/healthz)。
package health

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"time"
)

// Status 描述节点当前的身份、运行时规格与健康状态（不含任何凭据或敏感信息）。
type Status struct {
	ProtocolVersion int    `json:"protocolVersion"`
	NodeID          string `json:"nodeId"`
	Pool            string `json:"pool"`
	RuntimeSpec     string `json:"runtimeSpec"`
	RuntimeVersion  string `json:"runtimeVersion"`
	State           string `json:"state"`
	Healthy         bool   `json:"healthy"`
	ActiveSlots     int    `json:"activeSlots"`
	LocalSlots      int    `json:"localSlots"`
	MaxSlots        int    `json:"maxSlots"`
}

// Provider 由专池 Worker 实现，实时返回只读状态快照。
type Provider func() Status

// Handler 返回仅开放 GET /healthz 的只读 HTTP Handler。
func Handler(provider Provider) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			w.Header().Set("Allow", http.MethodGet)
			http.Error(w, `{"code":"METHOD_NOT_ALLOWED"}`, http.StatusMethodNotAllowed)
			return
		}
		st := provider()
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		if !st.Healthy {
			w.WriteHeader(http.StatusServiceUnavailable)
		} else {
			w.WriteHeader(http.StatusOK)
		}
		_ = json.NewEncoder(w).Encode(st)
	})
	return mux
}

// Serve 启动内网健康探测监听并在 ctx 取消时优雅关闭。
func Serve(ctx context.Context, addr string, provider Provider) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	srv := &http.Server{
		Handler:           Handler(provider),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       5 * time.Second,
		WriteTimeout:      5 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutCtx)
	}()
	err = srv.Serve(ln)
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}
