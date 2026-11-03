// Package queue 封装判题机与 Redis 之间的消息队列与状态缓存交互。
package queue

import (
	"context"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/zheco68-collab/KECE/judger/internal/config"
)

// Queue 为 Redis 队列客户端。
type Queue struct {
	client *redis.Client
	cfg    config.Config
}

// New 创建队列客户端并返回连接检查结果。
func New(cfg config.Config) *Queue {
	client := redis.NewClient(&redis.Options{
		Addr:     cfg.RedisAddr,
		Password: cfg.RedisPassword,
	})
	return &Queue{client: client, cfg: cfg}
}

// Ping 检查 Redis 连接。
func (q *Queue) Ping(ctx context.Context) error {
	return q.client.Ping(ctx).Err()
}

// Close 关闭底层连接。
func (q *Queue) Close() error {
	return q.client.Close()
}

// Take 原子地从待判队列取出一个任务到处理中列表。
// 超时返回 ok=false；其余错误直接返回。
func (q *Queue) Take(ctx context.Context) (taskJSON string, ok bool, err error) {
	v, err := q.client.BLMove(ctx, q.cfg.QueueKey, q.cfg.ProcessingKey, "LEFT", "LEFT", q.cfg.TakeTimeout).Result()
	if err == redis.Nil {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return v, true, nil
}

// PublishStatus 写入提交状态缓存（覆盖并刷新 TTL）。
func (q *Queue) PublishStatus(ctx context.Context, submissionID int64, statusJSON string) error {
	return q.client.Set(ctx, q.cfg.StatusKeyPrefix+itoa(submissionID), statusJSON, q.cfg.StatusTTL).Err()
}

// PublishTerminal atomically updates the UI cache and enqueues a durable copy
// of the terminal result. Persistence can finish even after the cache expires.
func (q *Queue) PublishTerminal(ctx context.Context, submissionID int64, statusJSON string) error {
	id := itoa(submissionID)
	_, err := q.client.TxPipelined(ctx, func(pipe redis.Pipeliner) error {
		pipe.Set(ctx, q.cfg.StatusKeyPrefix+id, statusJSON, q.cfg.StatusTTL)
		pipe.RPush(ctx, q.cfg.ResultsKey, id+"\n"+statusJSON)
		return nil
	})
	return err
}

// SetLease 为提交设置处理租约，供宕机重投判断存活。
func (q *Queue) SetLease(ctx context.Context, submissionID int64) error {
	return q.client.Set(ctx, q.cfg.LeaseKeyPrefix+itoa(submissionID), q.cfg.WorkerID, q.cfg.LeaseTTL).Err()
}

// ClearLease 移除处理租约。
func (q *Queue) ClearLease(ctx context.Context, submissionID int64) error {
	return q.client.Del(ctx, q.cfg.LeaseKeyPrefix+itoa(submissionID)).Err()
}

// HasLease 判断提交是否仍持有有效租约（表示仍有判题机在处理）。
func (q *Queue) HasLease(ctx context.Context, submissionID int64) (bool, error) {
	n, err := q.client.Exists(ctx, q.cfg.LeaseKeyPrefix+itoa(submissionID)).Result()
	return n > 0, err
}

// RemoveProcessing 从处理中列表移除一个任务。
func (q *Queue) RemoveProcessing(ctx context.Context, taskJSON string) error {
	return q.client.LRem(ctx, q.cfg.ProcessingKey, 1, taskJSON).Err()
}

// Requeue 将任务重新放回待判队列并从处理中列表移除（用于宕机重投）。
func (q *Queue) Requeue(ctx context.Context, taskJSON string) error {
	return q.client.Eval(ctx, requeueExpired, []string{q.cfg.QueueKey, q.cfg.ProcessingKey}, taskJSON).Err()
}

const requeueExpired = `
if redis.call('LREM', KEYS[2], 1, ARGV[1]) > 0 then
    redis.call('LPUSH', KEYS[1], ARGV[1])
end
`

// ListProcessing 返回处理中列表的全部任务（重投扫描用）。
func (q *Queue) ListProcessing(ctx context.Context) ([]string, error) {
	return q.client.LRange(ctx, q.cfg.ProcessingKey, 0, -1).Result()
}

// Heartbeat 上报判题机存活心跳并将 Worker 登记至全局注册集合。
func (q *Queue) Heartbeat(ctx context.Context) error {
	pipe := q.client.TxPipeline()
	pipe.Set(ctx, q.cfg.HeartbeatKey+q.cfg.WorkerID, time.Now().Format(time.RFC3339), q.cfg.HeartbeatInterval*3)
	pipe.SAdd(ctx, "judge:workers", q.cfg.WorkerID)
	_, err := pipe.Exec(ctx)
	return err
}

// ClearHeartbeat 移除判题机存活心跳键并从全局注册集合移除（用于优雅停机即刻变更为掉线状态）。
func (q *Queue) ClearHeartbeat(ctx context.Context) error {
	pipe := q.client.TxPipeline()
	pipe.Del(ctx, q.cfg.HeartbeatKey+q.cfg.WorkerID)
	pipe.SRem(ctx, "judge:workers", q.cfg.WorkerID)
	_, err := pipe.Exec(ctx)
	return err
}

func itoa(n int64) string {
	return strconv.FormatInt(n, 10)
}
