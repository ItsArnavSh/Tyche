package producer

import (
	"context"
	"fmt"
	"log"
	"sort"
	"sync"
	"time"

	"gateway/app/config"
	"gateway/app/internal/grpc"
	"gateway/app/status"
	"gateway/app/util/entity"
)

// bootBatchSize is 25 symbols x 5 candle sizes. It keeps each gRPC boot
// message bounded while still amortising the call overhead for large universes.
const bootBatchSize = 25 * 5

const (
	defaultRebootBaseBackoff = 10 * time.Second
	defaultRebootMaxBackoff  = 2 * time.Minute
)

// coreClient is the subset of the gRPC client used by the market-data pump. It
// is an interface so the re-boot state machine can be tested without a server.
type coreClient interface {
	SendBootRequest(context.Context, []entity.TickerHistory) error
	SendRollData(context.Context, []entity.LatestVal) ([]entity.MonoCandle, error)
}

// rebootPolicy controls retries for series that could not be booted. Permanent
// failures reported by the producer are never retried. MaxAttempts == 0 means
// transient failures are retried indefinitely with capped exponential backoff.
type rebootPolicy struct {
	BaseBackoff time.Duration
	MaxBackoff  time.Duration
	MaxAttempts int
}

func defaultRebootPolicy() rebootPolicy {
	policy := rebootPolicy{
		BaseBackoff: config.EnvDuration("TYCHE_REBOOT_BACKOFF_MS", defaultRebootBaseBackoff),
		MaxBackoff:  config.EnvDuration("TYCHE_REBOOT_MAX_BACKOFF_MS", defaultRebootMaxBackoff),
		MaxAttempts: config.EnvInt("TYCHE_REBOOT_MAX_ATTEMPTS", 0),
	}
	if policy.BaseBackoff <= 0 {
		policy.BaseBackoff = defaultRebootBaseBackoff
	}
	if policy.MaxBackoff < policy.BaseBackoff {
		policy.MaxBackoff = policy.BaseBackoff
	}
	if policy.MaxAttempts < 0 {
		policy.MaxAttempts = 0
	}
	return policy
}

func (p rebootPolicy) delay(attempt int) time.Duration {
	if attempt < 1 {
		attempt = 1
	}
	delay := p.BaseBackoff
	for i := 1; i < attempt; i++ {
		if p.MaxBackoff > 0 && delay >= p.MaxBackoff/2 {
			delay = p.MaxBackoff
			break
		}
		delay *= 2
	}
	if p.MaxBackoff > 0 && delay > p.MaxBackoff {
		delay = p.MaxBackoff
	}
	if delay <= 0 {
		delay = defaultRebootBaseBackoff
	}
	return delay
}

type rebootState struct {
	attempts    int
	nextAttempt time.Time
	inFlight    bool
	permanent   bool
	lastError   string
}

type bootResult struct {
	accepted  map[entity.MonoCandle]struct{}
	permanent map[entity.MonoCandle]struct{}
	err       error
}

// TradeInterface pumps market data from a Producer into the Java core.
type TradeInterface struct {
	lock     sync.Mutex
	client   coreClient
	prod     Producer
	recorder status.Recorder
	request  []entity.MonoCandle

	// Re-boot work is serialised by one goroutine. The map holds every series
	// that is queued, in flight, backing off, or permanently failed. A series
	// is removed from the map only after the core accepted its history.
	reboots       map[entity.MonoCandle]*rebootState
	rebootRunning bool
	rebootWake    chan struct{}
	rebootPolicy  rebootPolicy
}

func NewTradeInterface(client *grpc.GrpcClient, prod Producer, recorders ...status.Recorder) *TradeInterface {
	var recorder status.Recorder
	if len(recorders) > 0 {
		recorder = recorders[0]
	}
	return &TradeInterface{
		client:       client,
		prod:         prod,
		recorder:     recorder,
		reboots:      make(map[entity.MonoCandle]*rebootState),
		rebootWake:   make(chan struct{}, 1),
		rebootPolicy: defaultRebootPolicy(),
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
				Series:  monoCandleSeries(missing),
			})
			if len(missing) == 0 {
				continue
			}

			t.lock.Lock()
			t.request = removeCandles(t.request, missing)
			t.lock.Unlock()

			// Re-boot stale series without blocking the roll loop. The worker
			// coalesces duplicate missing reports and retries with backoff.
			t.enqueueReboots(ctx, missing)
		}
	}
}

func (t *TradeInterface) boot(ctx context.Context, req []entity.MonoCandle) {
	if len(req) == 0 {
		return
	}
	var retry []entity.MonoCandle
	for _, chunk := range chunkCandles(req, bootBatchSize) {
		result := t.sendBoot(ctx, chunk, "boot")
		retry = append(retry, t.applyBootResult(chunk, result, false)...)
	}
	t.enqueueReboots(ctx, retry)
}

// applyBootResult updates request/retry state after one boot batch. It returns
// transient failures so the caller can start the re-boot worker; permanent
// failures are dropped immediately and stay visible in status.
func (t *TradeInterface) applyBootResult(batch []entity.MonoCandle, result bootResult, countAttempts bool) []entity.MonoCandle {
	now := time.Now()
	retry := make([]entity.MonoCandle, 0)
	t.lock.Lock()
	t.initRebootStateLocked()
	for _, item := range batch {
		state := t.reboots[item]
		if state == nil {
			state = &rebootState{}
			t.reboots[item] = state
		}
		state.inFlight = false

		if _, accepted := result.accepted[item]; accepted {
			delete(t.reboots, item)
			if !contains(t.request, item) {
				t.request = append(t.request, item)
			}
			continue
		}

		state.attempts++
		_, permanent := result.permanent[item]
		switch {
		case permanent:
			state.permanent = true
			state.lastError = "permanent boot failure"
			t.request = removeCandles(t.request, []entity.MonoCandle{item})
			log.Printf("[TradeInterface] blacklisted %s: permanent boot failure", seriesLabel(item))
		case t.rebootPolicy.MaxAttempts > 0 && state.attempts >= t.rebootPolicy.MaxAttempts:
			state.permanent = true
			state.lastError = fmt.Sprintf("gave up after %d attempts", state.attempts)
			t.request = removeCandles(t.request, []entity.MonoCandle{item})
			log.Printf("[TradeInterface] blacklisted %s after %d attempts", seriesLabel(item), state.attempts)
		default:
			state.nextAttempt = now.Add(t.rebootPolicy.delay(state.attempts))
			if result.err != nil {
				state.lastError = result.err.Error()
			} else {
				state.lastError = "no history returned"
			}
			retry = append(retry, item)
		}
	}
	if countAttempts {
		t.recordRebootStatusLocked(now, len(batch), len(result.accepted), len(batch)-len(result.accepted))
	} else {
		t.recordRebootStatusLocked(now, 0, 0, 0)
	}
	t.lock.Unlock()
	return retry
}

func (t *TradeInterface) sendBoot(ctx context.Context, req []entity.MonoCandle, scope string) bootResult {
	result := bootResult{
		accepted:  make(map[entity.MonoCandle]struct{}),
		permanent: make(map[entity.MonoCandle]struct{}),
	}
	histories := t.prod.BootRequest(req)
	for _, history := range histories {
		if history.Name == "" || len(history.History.Data) == 0 {
			continue
		}
		item := entity.MonoCandle{Name: history.Name, Size: history.History.Size}
		if contains(req, item) {
			result.accepted[item] = struct{}{}
		}
	}
	if classifier, ok := t.prod.(PermanentBootFailureReporter); ok {
		for _, item := range req {
			if _, accepted := result.accepted[item]; accepted {
				continue
			}
			if classifier.PermanentBootFailure(item.Name, item.Size) {
				result.permanent[item] = struct{}{}
			}
		}
	}
	if len(histories) == 0 {
		log.Printf("[TradeInterface] %s produced no histories for %d jobs; scheduling retry", scope, len(req))
		return result
	}
	if err := t.client.SendBootRequest(ctx, histories); err != nil {
		// Histories were not delivered, so none of the batch was accepted.
		result.accepted = make(map[entity.MonoCandle]struct{})
		result.err = err
		t.recordRoll(status.RollEvent{At: time.Now(), Updates: len(histories), Boot: true, Failed: true, ErrorMsg: err.Error()})
		t.recordError(scope, err)
		log.Printf("[TradeInterface] %s send failed: %v", scope, err)
		return result
	}
	t.recordRoll(status.RollEvent{At: time.Now(), Updates: len(histories), Boot: true})
	log.Printf("[TradeInterface] %s: booted %d series for %d jobs", scope, len(histories), len(req))
	return result
}

func (t *TradeInterface) enqueueReboots(ctx context.Context, missing []entity.MonoCandle) {
	if len(missing) == 0 || ctx.Err() != nil {
		return
	}
	now := time.Now()
	changed := false
	launch := false
	t.lock.Lock()
	t.initRebootStateLocked()
	for _, item := range missing {
		if item.Name == "" {
			continue
		}
		state := t.reboots[item]
		if state != nil {
			continue
		}
		t.reboots[item] = &rebootState{nextAttempt: now}
		changed = true
	}
	if !t.rebootRunning && t.hasPendingLocked() {
		t.rebootRunning = true
		launch = true
	}
	t.lock.Unlock()

	if launch {
		go t.rebootLoop(ctx)
		return
	}
	if changed {
		t.wakeReboot()
	}
}

func (t *TradeInterface) rebootLoop(ctx context.Context) {
	timer := time.NewTimer(time.Hour)
	if !timer.Stop() {
		<-timer.C
	}
	defer timer.Stop()

	for {
		if ctx.Err() != nil {
			t.markRebootWorkerStopped()
			return
		}

		batch, wait, pending := t.takeReadyReboots(time.Now())
		if !pending {
			if t.stopRebootWorkerIfIdle() {
				return
			}
			continue
		}
		if len(batch) == 0 {
			if wait <= 0 {
				wait = 10 * time.Millisecond
			}
			timer.Reset(wait)
			select {
			case <-ctx.Done():
				if !timer.Stop() {
					select {
					case <-timer.C:
					default:
					}
				}
				t.markRebootWorkerStopped()
				return
			case <-t.rebootWake:
				if !timer.Stop() {
					select {
					case <-timer.C:
					default:
					}
				}
			case <-timer.C:
			}
			continue
		}

		result := t.sendBoot(ctx, batch, "re-boot")
		t.applyBootResult(batch, result, true)
	}
}

func (t *TradeInterface) takeReadyReboots(now time.Time) (batch []entity.MonoCandle, wait time.Duration, hasPending bool) {
	t.lock.Lock()
	defer t.lock.Unlock()
	t.initRebootStateLocked()

	var earliest time.Time
	for item, state := range t.reboots {
		if state.permanent || state.inFlight {
			continue
		}
		if state.nextAttempt.IsZero() || !state.nextAttempt.After(now) {
			batch = append(batch, item)
			continue
		}
		if earliest.IsZero() || state.nextAttempt.Before(earliest) {
			earliest = state.nextAttempt
		}
	}
	hasPending = len(batch) > 0 || !earliest.IsZero()
	if len(batch) == 0 {
		if hasPending {
			wait = time.Until(earliest)
			if wait <= 0 {
				wait = 10 * time.Millisecond
			}
		}
		return batch, wait, hasPending
	}

	sort.Slice(batch, func(i, j int) bool {
		if batch[i].Name != batch[j].Name {
			return batch[i].Name < batch[j].Name
		}
		return batch[i].Size < batch[j].Size
	})
	if len(batch) > bootBatchSize {
		batch = batch[:bootBatchSize]
	}
	for _, item := range batch {
		t.reboots[item].inFlight = true
	}
	t.recordRebootStatusLocked(now, 0, 0, 0)
	return batch, 0, true
}

func (t *TradeInterface) stopRebootWorkerIfIdle() bool {
	t.lock.Lock()
	defer t.lock.Unlock()
	if t.hasPendingLocked() {
		return false
	}
	t.rebootRunning = false
	return true
}

func (t *TradeInterface) markRebootWorkerStopped() {
	t.lock.Lock()
	t.rebootRunning = false
	t.lock.Unlock()
}

func (t *TradeInterface) hasPendingLocked() bool {
	for _, state := range t.reboots {
		if !state.permanent {
			return true
		}
	}
	return false
}

func (t *TradeInterface) initRebootStateLocked() {
	if t.reboots == nil {
		t.reboots = make(map[entity.MonoCandle]*rebootState)
	}
	if t.rebootWake == nil {
		t.rebootWake = make(chan struct{}, 1)
	}
	if t.rebootPolicy.BaseBackoff <= 0 {
		t.rebootPolicy.BaseBackoff = defaultRebootBaseBackoff
	}
	if t.rebootPolicy.MaxBackoff < t.rebootPolicy.BaseBackoff {
		t.rebootPolicy.MaxBackoff = t.rebootPolicy.BaseBackoff
	}
}

func (t *TradeInterface) wakeReboot() {
	t.lock.Lock()
	wake := t.rebootWake
	t.lock.Unlock()
	if wake == nil {
		return
	}
	select {
	case wake <- struct{}{}:
	default:
	}
}

func (t *TradeInterface) recordRebootStatusLocked(at time.Time, attempted, succeeded, failed int) {
	if t.recorder == nil {
		return
	}
	inFlight := 0
	pending := 0
	permanent := make([]string, 0)
	for item, state := range t.reboots {
		if state.permanent {
			permanent = append(permanent, seriesLabel(item))
			continue
		}
		if state.inFlight {
			inFlight++
		} else {
			pending++
		}
	}
	sort.Strings(permanent)
	t.recorder.RecordReboot(status.RebootEvent{
		At:        at,
		Attempted: attempted,
		Succeeded: succeeded,
		Failed:    failed,
		InFlight:  inFlight,
		Pending:   pending,
		Permanent: permanent,
	})
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

func monoCandleSeries(items []entity.MonoCandle) []string {
	out := make([]string, 0, len(items))
	seen := make(map[string]struct{}, len(items))
	for _, item := range items {
		label := seriesLabel(item)
		if _, ok := seen[label]; ok {
			continue
		}
		seen[label] = struct{}{}
		out = append(out, label)
	}
	sort.Strings(out)
	return out
}

func seriesLabel(item entity.MonoCandle) string {
	return fmt.Sprintf("%s:%s", item.Name, item.Size)
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
