package producer

import (
	"context"
	"sync"
	"testing"
	"time"

	"gateway/app/status"
	"gateway/app/util/entity"
)

type fakeCoreClient struct {
	mu        sync.Mutex
	histories []entity.TickerHistory
	err       error
}

func (f *fakeCoreClient) SendBootRequest(_ context.Context, histories []entity.TickerHistory) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.err != nil {
		return f.err
	}
	f.histories = append(f.histories, histories...)
	return nil
}

func (f *fakeCoreClient) SendRollData(_ context.Context, _ []entity.LatestVal) ([]entity.MonoCandle, error) {
	return nil, nil
}

type fakeBootProducer struct {
	mu        sync.Mutex
	symbols   []string
	bootCalls int
	bootFn    func(call int, req []entity.MonoCandle) []entity.TickerHistory
	permanent map[entity.MonoCandle]bool
}

func (p *fakeBootProducer) BootRequest(req []entity.MonoCandle) []entity.TickerHistory {
	p.mu.Lock()
	p.bootCalls++
	call := p.bootCalls
	fn := p.bootFn
	p.mu.Unlock()
	if fn != nil {
		return fn(call, req)
	}
	return historiesFor(req)
}

func (p *fakeBootProducer) RollRequest(_ []entity.MonoCandle) []entity.LatestVal { return nil }
func (p *fakeBootProducer) GetCurrentValue(_ string) float64                     { return 100 }
func (p *fakeBootProducer) Symbols() []string                                    { return p.symbols }
func (p *fakeBootProducer) RollInterval() time.Duration                          { return time.Second }
func (p *fakeBootProducer) Close() error                                         { return nil }

func (p *fakeBootProducer) PermanentBootFailure(name string, size entity.CandleSize) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.permanent[entity.MonoCandle{Name: name, Size: size}]
}

func (p *fakeBootProducer) calls() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.bootCalls
}

type recordingRecorder struct {
	mu      sync.Mutex
	rolls   []status.RollEvent
	reboots []status.RebootEvent
}

func (r *recordingRecorder) RecordSignal(_ entity.Signal)                                   {}
func (r *recordingRecorder) RecordOrder(_ entity.TransactionInstruction, _ string, _ error) {}
func (r *recordingRecorder) RecordClosedTrade(_ status.ClosedTrade)                         {}
func (r *recordingRecorder) RecordRoll(roll status.RollEvent) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.rolls = append(r.rolls, roll)
}
func (r *recordingRecorder) RecordReboot(reboot status.RebootEvent) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.reboots = append(r.reboots, reboot)
}
func (r *recordingRecorder) RecordError(_ string, _ error) {}

func (r *recordingRecorder) rebootTotals() (attempts, succeeded, failed int) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, reboot := range r.reboots {
		attempts += reboot.Attempted
		succeeded += reboot.Succeeded
		failed += reboot.Failed
	}
	return attempts, succeeded, failed
}

func (r *recordingRecorder) permanentSeries() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	for i := len(r.reboots) - 1; i >= 0; i-- {
		if len(r.reboots[i].Permanent) > 0 {
			return append([]string(nil), r.reboots[i].Permanent...)
		}
	}
	return nil
}

func newTestTradeInterface(prod Producer, client coreClient, recorder status.Recorder) *TradeInterface {
	return &TradeInterface{
		client:       client,
		prod:         prod,
		recorder:     recorder,
		request:      make([]entity.MonoCandle, 0),
		reboots:      make(map[entity.MonoCandle]*rebootState),
		rebootWake:   make(chan struct{}, 1),
		rebootPolicy: rebootPolicy{BaseBackoff: 5 * time.Millisecond, MaxBackoff: 20 * time.Millisecond},
	}
}

func historiesFor(req []entity.MonoCandle) []entity.TickerHistory {
	out := make([]entity.TickerHistory, 0, len(req))
	for _, item := range req {
		out = append(out, entity.TickerHistory{
			Name: item.Name,
			History: entity.CandleSeries{
				Size: item.Size,
				Data: []entity.CandleData{{Open: 100, High: 101, Low: 99, Close: 100}},
			},
		})
	}
	return out
}

func waitFor(t *testing.T, timeout time.Duration, check func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if check() {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatal("condition not met before timeout")
}

func TestRebootWorkerCoalescesDuplicateMissingReports(t *testing.T) {
	item := entity.MonoCandle{Name: "TEST.NS", Size: entity.MIN1}
	prod := &fakeBootProducer{
		bootFn: func(call int, req []entity.MonoCandle) []entity.TickerHistory {
			if call == 1 {
				return nil
			}
			return historiesFor(req)
		},
	}
	client := &fakeCoreClient{}
	recorder := &recordingRecorder{}
	trade := newTestTradeInterface(prod, client, recorder)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	trade.enqueueReboots(ctx, []entity.MonoCandle{item})
	trade.enqueueReboots(ctx, []entity.MonoCandle{item})

	waitFor(t, 2*time.Second, func() bool {
		trade.lock.Lock()
		defer trade.lock.Unlock()
		_, stillPending := trade.reboots[item]
		return contains(trade.request, item) && !stillPending
	})

	if calls := prod.calls(); calls != 2 {
		t.Fatalf("boot attempts = %d, want 2 (one failure + one retry)", calls)
	}
	attempts, succeeded, failed := recorder.rebootTotals()
	if attempts != 2 || succeeded != 1 || failed != 1 {
		t.Fatalf("reboot totals = attempts:%d succeeded:%d failed:%d", attempts, succeeded, failed)
	}
}

func TestRebootWorkerBlacklistsPermanentFailure(t *testing.T) {
	item := entity.MonoCandle{Name: "MISSING.NS", Size: entity.MIN1}
	prod := &fakeBootProducer{
		bootFn: func(_ int, _ []entity.MonoCandle) []entity.TickerHistory { return nil },
		permanent: map[entity.MonoCandle]bool{
			item: true,
		},
	}
	client := &fakeCoreClient{}
	recorder := &recordingRecorder{}
	trade := newTestTradeInterface(prod, client, recorder)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	trade.enqueueReboots(ctx, []entity.MonoCandle{item})

	waitFor(t, time.Second, func() bool {
		trade.lock.Lock()
		defer trade.lock.Unlock()
		state := trade.reboots[item]
		return state != nil && state.permanent
	})
	time.Sleep(30 * time.Millisecond)

	if calls := prod.calls(); calls != 1 {
		t.Fatalf("permanent failure was retried: boot attempts = %d", calls)
	}
	trade.lock.Lock()
	inRequest := contains(trade.request, item)
	trade.lock.Unlock()
	if inRequest {
		t.Fatal("permanent failure was re-added to the roll request")
	}
	if got := recorder.permanentSeries(); len(got) != 1 || got[0] != "MISSING.NS:min1" {
		t.Fatalf("permanent series = %v", got)
	}
	attempts, succeeded, failed := recorder.rebootTotals()
	if attempts != 1 || succeeded != 0 || failed != 1 {
		t.Fatalf("reboot totals = attempts:%d succeeded:%d failed:%d", attempts, succeeded, failed)
	}
}

func TestBootRetriesTransientFailure(t *testing.T) {
	item := entity.MonoCandle{Name: "FLAKY.NS", Size: entity.MIN15}
	prod := &fakeBootProducer{
		bootFn: func(call int, req []entity.MonoCandle) []entity.TickerHistory {
			if call == 1 {
				return nil
			}
			return historiesFor(req)
		},
	}
	client := &fakeCoreClient{}
	recorder := &recordingRecorder{}
	trade := newTestTradeInterface(prod, client, recorder)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	trade.boot(ctx, []entity.MonoCandle{item})

	waitFor(t, 2*time.Second, func() bool {
		trade.lock.Lock()
		defer trade.lock.Unlock()
		_, stillPending := trade.reboots[item]
		return contains(trade.request, item) && !stillPending
	})
	if calls := prod.calls(); calls != 2 {
		t.Fatalf("boot attempts = %d, want 2", calls)
	}
}
