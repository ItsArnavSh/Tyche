package redis

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

var client *redis.Client

func init() {
	client = redis.NewClient(&redis.Options{
		Addr:         "localhost:6379",
		Password:     "",
		DB:           0,
		PoolSize:     16,
		MinIdleConns: 2,
	})
}

// ── Key helper ────────────────────────────────────────────────
// Produces keys like "Monitoring:Thread1"
func key(namespace, field string) string {
	return fmt.Sprintf("%s:%s", namespace, field)
}

// ── Write ─────────────────────────────────────────────────────

// Set a single key in a namespace.
func Set(ctx context.Context, namespace, field, value string) error {
	return client.Set(ctx, key(namespace, field), value, 0).Err()
}

// SetEx sets a single key with a TTL.
func SetEx(ctx context.Context, namespace, field, value string, ttl time.Duration) error {
	return client.Set(ctx, key(namespace, field), value, ttl).Err()
}

// SetAll sets multiple fields in a namespace at once.
func SetAll(ctx context.Context, namespace string, entries map[string]string) error {
	pipe := client.Pipeline()
	for field, value := range entries {
		pipe.Set(ctx, key(namespace, field), value, 0)
	}
	_, err := pipe.Exec(ctx)
	return err
}

// ── Read ──────────────────────────────────────────────────────

// Get a single value. Returns ("", false, nil) if the key doesn't exist.
func Get(ctx context.Context, namespace, field string) (string, bool, error) {
	val, err := client.Get(ctx, key(namespace, field)).Result()
	if err == redis.Nil {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return val, true, nil
}

// GetAll returns all field→value pairs in a namespace.
func GetAll(ctx context.Context, namespace string) (map[string]string, error) {
	pattern := namespace + ":*"
	prefixLen := len(namespace) + 1 // strip "Namespace:"

	keys, err := client.Keys(ctx, pattern).Result()
	if err != nil {
		return nil, err
	}

	if len(keys) == 0 {
		return map[string]string{}, nil
	}

	// Batch fetch all values in one round-trip
	pipe := client.Pipeline()
	cmds := make([]*redis.StringCmd, len(keys))
	for i, k := range keys {
		cmds[i] = pipe.Get(ctx, k)
	}
	pipe.Exec(ctx)

	result := make(map[string]string, len(keys))
	for i, k := range keys {
		field := k[prefixLen:]
		val, err := cmds[i].Result()
		if err == nil {
			result[field] = val
		}
	}
	return result, nil
}

// ── Delete ────────────────────────────────────────────────────

// Delete removes a single field from a namespace.
func Delete(ctx context.Context, namespace, field string) error {
	return client.Del(ctx, key(namespace, field)).Err()
}

// DeleteAll removes all keys in a namespace.
func DeleteAll(ctx context.Context, namespace string) error {
	keys, err := client.Keys(ctx, namespace+":*").Result()
	if err != nil {
		return err
	}
	if len(keys) == 0 {
		return nil
	}
	return client.Del(ctx, keys...).Err()
}

func Exists(ctx context.Context, namespace, field string) (bool, error) {
	n, err := client.Exists(ctx, key(namespace, field)).Result()
	return n > 0, err
}

func Close() error {
	return client.Close()
}

func GetAllScan(ctx context.Context, namespace string) (map[string]string, error) {
	pattern := namespace + ":*"
	prefixLen := len(namespace) + 1

	result := make(map[string]string)
	iter := client.Scan(ctx, 0, pattern, 0).Iterator()

	for iter.Next(ctx) {
		k := iter.Val()
		val, err := client.Get(ctx, k).Result()
		if err == nil {
			field := strings.TrimPrefix(k[:], k[:prefixLen])
			result[field] = val
		}
	}
	return result, iter.Err()
}
