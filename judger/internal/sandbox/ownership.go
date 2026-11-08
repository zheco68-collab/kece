package sandbox

import (
	"context"
	"fmt"
	"strconv"
	"strings"
)

type ownerKey struct{}

type containerOwner struct {
	nodeID, jobID            string
	sessionEpoch, leaseEpoch int64
}

// WithOwner labels containers with the node and lease responsible for their execution.
func WithOwner(ctx context.Context, nodeID, jobID string, sessionEpoch, leaseEpoch int64) context.Context {
	return context.WithValue(ctx, ownerKey{}, containerOwner{nodeID, jobID, sessionEpoch, leaseEpoch})
}

// CleanupPreviousContainers reaps containers from sessions fenced by the current boot.
func (r *Runner) CleanupPreviousContainers(ctx context.Context, nodeID string, sessionEpoch int64) error {
	list, detail, code, err := runDocker(ctx, []string{
		"ps", "-a", "--filter", "label=kece.managed=true", "--filter", "label=kece.node=" + nodeID,
		"--format", `{{.ID}} {{.Label "kece.session"}}`,
	})
	if err != nil {
		return fmt.Errorf("list previous sandbox containers: %w", err)
	}
	if code != 0 {
		return fmt.Errorf("list previous sandbox containers (%d): %s", code, detail)
	}
	for _, line := range strings.Split(list, "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 {
			continue
		}
		epoch, parseErr := strconv.ParseInt(fields[1], 10, 64)
		if parseErr != nil || epoch <= 0 || epoch >= sessionEpoch {
			continue
		}
		_, detail, code, err := runDocker(ctx, []string{"rm", "-f", fields[0]})
		if err != nil {
			return fmt.Errorf("remove previous sandbox container: %w", err)
		}
		if code != 0 && !strings.Contains(detail, "No such container") {
			return fmt.Errorf("remove previous sandbox container (%d): %s", code, detail)
		}
	}
	return nil
}
