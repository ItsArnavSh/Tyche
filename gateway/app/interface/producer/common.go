package producer

import (
	"context"
	"log"
	"sync"
	"time"

	"gateway/app/internal/grpc"
	"gateway/app/util/entity"
)

// TradeInterface pumps market data from a Producer into the Java core.
type TradeInterface struct {
	lock    sync.Mutex
	client  *grpc.GrpcClient
	prod    Producer
	request []entity.MonoCandle
}

func NewTradeInterface(client *grpc.GrpcClient, prod Producer) *TradeInterface {
	return &TradeInterface{
		client: client,
		prod:   prod,
	}
}

func (t *TradeInterface) IllDoTheTalking(ctx context.Context) {
	t.lock.Lock()
	t.request = BuildAllMonoCandles(t.prod.Symbols())
	initial := append([]entity.MonoCandle(nil), t.request...)
	t.lock.Unlock()
	if len(initial) == 0 {
		log.Println("[TradeInterface] no symbols configured — nothing to stream")
		return
	}

	t.boot(ctx, initial)

	interval := t.prod.RollInterval()
	if interval <= 0 {
		interval = 5 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			log.Println("[TradeInterface] context cancelled — stopping")
			return
		case <-ticker.C:
			t.lock.Lock()
			current := append([]entity.MonoCandle(nil), t.request...)
			t.lock.Unlock()

			latest := t.prod.RollRequest(current)
			if len(latest) == 0 {
				continue
			}
			missing, err := t.client.SendRollData(ctx, latest)
			if err != nil {
				log.Printf("[TradeInterface] roll send failed: %v", err)
				continue
			}
			if len(missing) == 0 {
				continue
			}

			t.lock.Lock()
			t.request = removeCandles(t.request, missing)
			t.lock.Unlock()

			// Re-boot stale tickers without blocking the roll loop.
			go t.reboot(ctx, missing)
		}
	}
}

func (t *TradeInterface) boot(ctx context.Context, req []entity.MonoCandle) {
	histories := t.prod.BootRequest(req)
	if len(histories) == 0 {
		log.Println("[TradeInterface] boot produced no histories; wait for stale reboots")
		return
	}
	if err := t.client.SendBootRequest(ctx, histories); err != nil {
		log.Printf("[TradeInterface] boot send failed: %v", err)
		return
	}
	log.Printf("[TradeInterface] booted %d series for %d jobs", len(histories), len(req))
}

func (t *TradeInterface) reboot(ctx context.Context, missing []entity.MonoCandle) {
	histories := t.prod.BootRequest(missing)
	if len(histories) > 0 {
		if err := t.client.SendBootRequest(ctx, histories); err != nil {
			log.Printf("[TradeInterface] re-boot send failed: %v", err)
		}
	}
	t.lock.Lock()
	defer t.lock.Unlock()
	for _, item := range missing {
		if !contains(t.request, item) {
			t.request = append(t.request, item)
		}
	}
}

func contains(items []entity.MonoCandle, target entity.MonoCandle) bool {
	for _, item := range items {
		if item == target {
			return true
		}
	}
	return false
}

func BuildAllMonoCandles(names []string) []entity.MonoCandle {
	sizes := []entity.CandleSize{
		entity.SEC5,
		entity.SEC30,
		entity.MIN1,
		entity.MIN15,
		entity.HOUR1,
	}

	out := make([]entity.MonoCandle, 0, len(names)*len(sizes))
	for _, name := range names {
		for _, size := range sizes {
			out = append(out, entity.MonoCandle{Name: name, Size: size})
		}
	}
	return out
}

func removeCandles(all, rm []entity.MonoCandle) []entity.MonoCandle {
	out := make([]entity.MonoCandle, 0, len(all))
	for _, candidate := range all {
		if !contains(rm, candidate) {
			out = append(out, candidate)
		}
	}
	return out
}
