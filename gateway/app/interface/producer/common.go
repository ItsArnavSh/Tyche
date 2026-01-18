package producer

import (
	"context"
	"gateway/app/server/grpc"
	"gateway/app/util/entity"
	"sync"
	"time"
)

type TradeInterface struct {
	lock   sync.Mutex
	client *grpc.GrpcClient
	prod   Producer
}

func NewTradeInterface(client *grpc.GrpcClient, prod Producer) TradeInterface {
	return TradeInterface{
		client: client,
		prod:   prod,
		lock:   sync.Mutex{},
	}
}

func (t *TradeInterface) IllDoTheTalking(ctx context.Context) {
	req := BuildAllMonoCandles([]string{"ONGC", "BEL"})
	mu := &sync.Mutex{}

	// Initial boot load
	bootReq := t.prod.BootRequest(req)
	t.client.SendBootRequest(ctx, bootReq)

	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			// Fetch latest values
			latest := t.prod.RollRequest(req)
			missing, _ := t.client.SendRollData(ctx, latest)

			if len(missing) > 0 {
				// remove missing from main req list
				mu.Lock()
				req = removeCandles(req, missing)
				mu.Unlock()

				// fix missing async
				go func(miss []entity.MonoCandle) {
					hist := t.prod.BootRequest(miss)
					t.client.SendBootRequest(ctx, hist)

					mu.Lock()
					req = append(req, miss...)
					mu.Unlock()
				}(missing)
			}
		}
	}
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
	for _, n := range names {
		for _, s := range sizes {
			out = append(out, entity.MonoCandle{
				Name: n,
				Size: s,
			})
		}
	}
	return out
}

func removeCandles(all, rm []entity.MonoCandle) []entity.MonoCandle {
	out := all[:0]
	for _, c := range all {
		keep := true
		for _, r := range rm {
			if r.Name == c.Name && r.Size == c.Size {
				keep = false
				break
			}
		}
		if keep {
			out = append(out, c)
		}
	}
	return out
}
