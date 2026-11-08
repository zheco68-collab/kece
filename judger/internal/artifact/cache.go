// Package artifact downloads and verifies immutable judge inputs.
package artifact

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const maxArtifactBytes = 100 * 1024 * 1024

var digestPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

type Ref struct {
	Digest string `json:"digest"`
	Size   int64  `json:"size"`
}

type Fetch func(context.Context, Ref) (io.ReadCloser, error)

// Cache is owned by one worker process. Active handles pin files during execution.
type Cache struct {
	dir   string
	limit int64
	mu    sync.Mutex
	uses  map[string]*use
}

type use struct {
	refs int
	gate chan struct{}
}

type Handle struct {
	Path    string
	release func()
	once    sync.Once
}

func (h *Handle) Close() {
	h.once.Do(h.release)
}

func NewCache(directory string, limitBytes int64) (*Cache, error) {
	if limitBytes <= 0 {
		return nil, fmt.Errorf("cache limit must be positive")
	}
	dir, err := filepath.Abs(directory)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	for _, entry := range entries {
		name := entry.Name()
		if entry.Type().IsRegular() && strings.HasPrefix(name, ".download-") && strings.HasSuffix(name, ".part") {
			if err := os.Remove(filepath.Join(dir, name)); err != nil {
				return nil, err
			}
		}
	}
	cache := &Cache{dir: dir, limit: limitBytes, uses: make(map[string]*use)}
	if err := cache.Prune(); err != nil {
		return nil, err
	}
	return cache, nil
}

func (c *Cache) Acquire(ctx context.Context, ref Ref, fetch Fetch) (*Handle, error) {
	if !digestPattern.MatchString(ref.Digest) || ref.Size < 0 || ref.Size > maxArtifactBytes {
		return nil, fmt.Errorf("invalid artifact metadata")
	}
	c.mu.Lock()
	entry := c.uses[ref.Digest]
	if entry == nil {
		entry = &use{gate: make(chan struct{}, 1)}
		c.uses[ref.Digest] = entry
	}
	entry.refs++
	c.mu.Unlock()
	release := func() {
		c.mu.Lock()
		defer c.mu.Unlock()
		entry.refs--
		if entry.refs == 0 {
			delete(c.uses, ref.Digest)
		}
	}
	select {
	case entry.gate <- struct{}{}:
		defer func() { <-entry.gate }()
	case <-ctx.Done():
		release()
		return nil, ctx.Err()
	}
	if err := ctx.Err(); err != nil {
		release()
		return nil, err
	}

	path := filepath.Join(c.dir, ref.Digest)
	if err := verifyFile(path, ref); err != nil {
		if err := c.download(ctx, path, ref, fetch); err != nil {
			release()
			return nil, err
		}
	}
	_ = os.Chtimes(path, time.Now(), time.Now())
	return &Handle{Path: path, release: release}, nil
}

func verifyFile(path string, ref Ref) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Size() != ref.Size {
		return fmt.Errorf("artifact is not a regular file of the expected size")
	}
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	return copyVerified(io.Discard, file, ref)
}

func (c *Cache) download(ctx context.Context, path string, ref Ref, fetch Fetch) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	source, err := fetch(ctx, ref)
	if err != nil {
		return err
	}
	defer source.Close()
	staged, err := os.CreateTemp(c.dir, ".download-*.part")
	if err != nil {
		return err
	}
	defer os.Remove(staged.Name())
	defer staged.Close()
	if err := copyVerified(staged, source, ref); err != nil {
		return err
	}
	if err := staged.Sync(); err != nil {
		return err
	}
	if err := staged.Close(); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	return os.Rename(staged.Name(), path)
}

func copyVerified(target io.Writer, source io.Reader, ref Ref) error {
	hash := sha256.New()
	size, err := io.Copy(io.MultiWriter(target, hash), io.LimitReader(source, ref.Size+1))
	if err != nil {
		return err
	}
	if size != ref.Size || hex.EncodeToString(hash.Sum(nil)) != ref.Digest {
		return fmt.Errorf("artifact size or digest mismatch")
	}
	return nil
}

// Prune evicts idle entries until the cache is within its disk budget.
// A job may temporarily exceed the budget while its inputs are pinned.
func (c *Cache) Prune() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	entries, err := os.ReadDir(c.dir)
	if err != nil {
		return err
	}
	var total int64
	var idle []os.FileInfo
	for _, entry := range entries {
		if !digestPattern.MatchString(entry.Name()) {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() {
			continue
		}
		total += info.Size()
		if c.uses[info.Name()] == nil {
			idle = append(idle, info)
		}
	}
	sort.Slice(idle, func(i, j int) bool {
		return idle[i].ModTime().Before(idle[j].ModTime())
	})
	for _, info := range idle {
		if total <= c.limit {
			break
		}
		if err := os.Remove(filepath.Join(c.dir, info.Name())); err != nil {
			return err
		}
		total -= info.Size()
	}
	return nil
}
