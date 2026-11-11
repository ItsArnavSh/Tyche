package producer

import (
	"context"
	"log"
	"sort"
	"sync"
	"time"

	"gateway/app/internal/grpc"
	"gateway/app/status"
	"gateway/app/util/entity"
)

// bootBatchSize is 25 symbols x 5 candle sizes. It keeps each gRPC boot
// message bounded while still amortising the call overhead for large universes.
const bootBatchSize = 25 * 5

// TradeInterface pumps market data from a Producer into the Java core.
type TradeInterface struct {
	lock     sync.Mutex
	client   *grpc.GrpcClient
	prod     Producer
	recorder status.Recorder
	request  []entity.MonoCandle
}

func NewTradeInterface(client *grpc.GrpcClient, prod Producer, recorders ...status.Recorder) *TradeInterface {
	var recorder status.Recorder
	if len(recorders) > 0 {
		recorder = recorders[0]
	}
	return &TradeInterface{
		client:   client,
		prod:     prod,
		recorder: recorder,
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
				t.recordRoll(status.RollEvent{
					At: time.Now(), Updates: len(latest), Failed: true, ErrorMsg: err.Error(),
				})
				t.recordError("roll", err)
				log.Printf("[TradeInterface] roll send failed: %v", err)
				continue
			}
			t.recordRoll(status.RollEvent{
				At:      time.Now(),
				Updates: len(latest),
				Missing: len(missing),
				Tickers: monoCandleNames(missing),
			})
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
	if len(req) == 0 {
		return
	}
	for _, chunk := range chunkCandles(req, bootBatchSize) {
		t.sendBoot(ctx, chunk, "boot")
	}
}

func (t *TradeInterface) reboot(ctx context.Context, missing []entity.MonoCandle) {
	for _, chunk := range chunkCandles(missing, bootBatchSize) {
		t.sendBoot(ctx, chunk, "re-boot")
	}
	t.lock.Lock()
	defer t.lock.Unlock()
	for _, item := range missing {
		if !contains(t.request, item) {
			t.request = append(t.request, item)
		}
	}
}

func (t *TradeInterface) sendBoot(ctx context.Context, req []entity.MonoCandle, scope string) {
	histories := t.prod.BootRequest(req)
	if len(histories) == 0 {
		log.Printf("[TradeInterface] %s produced no histories for %d jobs; wait for stale reboots", scope, len(req))
		return
	}
	if err := t.client.SendBootRequest(ctx, histories); err != nil {
		t.recordRoll(status.RollEvent{At: time.Now(), Updates: len(histories), Boot: true, Failed: true, ErrorMsg: err.Error()})
		t.recordError(scope, err)
		log.Printf("[TradeInterface] %s send failed: %v", scope, err)
		return
	}
	t.recordRoll(status.RollEvent{At: time.Now(), Updates: len(histories), Boot: true})
	log.Printf("[TradeInterface] %s: booted %d series for %d jobs", scope, len(histories), len(req))
}

func (t *TradeInterface) recordRoll(roll status.RollEvent) {
	if t.recorder != nil {
		t.recorder.RecordRoll(roll)
	}
}

func (t *TradeInterface) recordError(scope string, err error) {
	if t.recorder != nil {
		t.recorder.RecordError(scope, err)
	}
}

func monoCandleNames(items []entity.MonoCandle) []string {
	out := make([]string, 0, len(items))
	seen := make(map[string]struct{}, len(items))
	for _, item := range items {
		if _, ok := seen[item.Name]; ok {
			continue
		}
		seen[item.Name] = struct{}{}
		out = append(out, item.Name)
	}
	sort.Strings(out)
	return out
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

func chunkCandles(items []entity.MonoCandle, size int) [][]entity.MonoCandle {
	if len(items) == 0 {
		return nil
	}
	if size <= 0 || len(items) <= size {
		return [][]entity.MonoCandle{items}
	}
	out := make([][]entity.MonoCandle, 0, (len(items)+size-1)/size)
	for start := 0; start < len(items); start += size {
		end := min(start+size, len(items))
		out = append(out, items[start:end])
	}
	return out
}
