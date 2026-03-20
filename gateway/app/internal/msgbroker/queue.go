package messagebroker

import (
	"context"
	"encoding/json"
	"fmt"
	"gateway/app/util/entity"
	"log"
	"time"

	"github.com/redis/go-redis/v9"
)

type RedisQueue struct {
	client    *redis.Client
	queueName string
}

// NewRedisQueue creates a new Redis queue client
func NewRedisQueue(addr, password string, db int, queueName string) (*RedisQueue, error) {
	client := redis.NewClient(&redis.Options{
		Addr:     addr,     // e.g., "localhost:6379"
		Password: password, // "" for no password
		DB:       db,       // 0 is default DB
	})

	// Test connection
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to connect to Redis: %w", err)
	}

	return &RedisQueue{
		client:    client,
		queueName: queueName,
	}, nil
}

// Poll polls the Redis queue forever and pushes signals to the provided channel
// This function blocks until ctx is cancelled
func (r *RedisQueue) Poll(ctx context.Context, signalChan chan<- entity.Signal) {
	timeout := 2 * time.Second // BLPOP timeout

	for {
		select {
		case <-ctx.Done():
			log.Println("Redis polling stopped")
			return
		default:
			result, err := r.client.BLPop(ctx, timeout, r.queueName).Result()
			if err != nil {
				if err == redis.Nil {
					// Timeout, no data available - continue polling
					continue
				}
				if err == context.Canceled || err == context.DeadlineExceeded {
					// Context cancelled
					return
				}
				// Log error and retry
				log.Printf("Error polling Redis: %v", err)
				time.Sleep(time.Second)
				continue
			}

			// result[0] is the queue name, result[1] is the data
			if len(result) < 2 {
				log.Printf("Invalid response from Redis: %v", result)
				continue
			}

			var signal entity.Signal
			if err := json.Unmarshal([]byte(result[1]), &signal); err != nil {
				log.Printf("Failed to unmarshal signal: %v", err)
				continue
			}

			// Push to channel (non-blocking)
			select {
			case signalChan <- signal:
				// Successfully sent
			case <-ctx.Done():
				return
			}
		}
	}
}

// Push sends a signal to the queue (for testing)
func (r *RedisQueue) Push(ctx context.Context, signal *entity.Signal) error {
	data, err := json.Marshal(signal)
	if err != nil {
		return fmt.Errorf("failed to marshal signal: %w", err)
	}

	return r.client.RPush(ctx, r.queueName, data).Err()
}

// QueueLength returns the current queue size
func (r *RedisQueue) QueueLength(ctx context.Context) (int64, error) {
	return r.client.LLen(ctx, r.queueName).Result()
}

// Clear removes all items from the queue
func (r *RedisQueue) Clear(ctx context.Context) error {
	return r.client.Del(ctx, r.queueName).Err()
}

// Close closes the Redis connection
func (r *RedisQueue) Close() error {
	return r.client.Close()
}
