package artifact

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
)

// LeaseSource limits artifact access to a single job and its opaque lease capability.
func LeaseSource(client *http.Client, controlURL string, jobID int64, token string) (Fetch, error) {
	base, err := url.Parse(controlURL)
	if client == nil || err != nil || (base.Scheme != "https" && base.Scheme != "http") || base.Host == "" ||
		base.User != nil || base.RawQuery != "" || base.Fragment != "" || jobID <= 0 || token == "" {
		return nil, fmt.Errorf("invalid artifact lease source")
	}
	bounded := *client
	bounded.CheckRedirect = func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}
	return func(ctx context.Context, ref Ref) (io.ReadCloser, error) {
		if !digestPattern.MatchString(ref.Digest) {
			return nil, fmt.Errorf("invalid artifact digest")
		}
		endpoint := base.JoinPath("internal", "judgers", "jobs", strconv.FormatInt(jobID, 10),
			"artifacts", ref.Digest)
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Accept-Encoding", "identity")
		response, err := bounded.Do(req)
		if err != nil {
			return nil, err
		}
		if response.StatusCode != http.StatusOK ||
			(response.ContentLength >= 0 && response.ContentLength != ref.Size) {
			response.Body.Close()
			return nil, fmt.Errorf("artifact download rejected: HTTP %d", response.StatusCode)
		}
		return response.Body, nil
	}, nil
}
