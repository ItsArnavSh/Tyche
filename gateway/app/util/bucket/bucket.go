package bucket

import (
	"fmt"
	"sort"
	"sync"
	"time"

	"gateway/app/interface/producer"
	"gateway/app/util/entity"
	util "gateway/app/util/gen_util"
)

// Bucket aggregates decaying trading signals per ticker.
//
// Each ticker keeps at most one live signal per strategy and timeframe; a
// fresh emission from the same producer replaces the previous one.
// Confidence decays exponentially and reaches ~1% of its initial value at
// the candle-size expiry, after which the signal is pruned.
type Bucket struct {
	mu       sync.RWMutex
	Signals  map[string]map[string]entity.Signal // ticker -> strategy@timeframe -> signal
	Producer producer.Producer
}

func NewBucket(p producer.Producer) *Bucket {
	return &Bucket{
		Signals:  make(map[string]map[string]entity.Signal),
		Producer: p,
	}
}

func (b *Bucket) SignalBucket(signal entity.Signal) {
	if signal.Name == "" || signal.Size < entity.SEC5 || signal.Size > entity.HOUR1 {
		return
	}
	if signal.Confidence > 1 && signal.Confidence <= 100 {
		signal.Confidence /= 100
	}
	signal = signal.Clamped()
	if signal.Time.IsZero() {
		signal.Time = time.Now()
	}
	now := time.Now()
	if signal.Expired(now) {
		return
	}

	b.mu.Lock()
	defer b.mu.Unlock()
	strategies := b.Signals[signal.Name]
	if strategies == nil {
		strategies = make(map[string]entity.Signal)
		b.Signals[signal.Name] = strategies
	}
	strategies[signal.StrategyKey()] = signal
}

// GetConfidence returns the aggregate decayed confidence for a ticker in
// [0, 1].  Unknown tickers return (0, nil).
func (b *Bucket) GetConfidence(name string) (float64, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	strategies, ok := b.Signals[name]
	if !ok {
		return 0, nil
	}
	return b.confidenceLocked(name, strategies, time.Now()), nil
}

func (b *Bucket) confidenceLocked(name string, strategies map[string]entity.Signal, now time.Time) float64 {
	total, _ := b.confidenceStatsLocked(name, strategies, now)
	return total
}

// confidenceStatsLocked returns the decayed aggregate and the strongest raw
// confidence among live signals for a ticker.
//
// Correlated strategies should not stack as if they were independent votes.
// We therefore take the strongest decayed signal in each family and add a
// small consensus bonus for additional live families.  The result stays in
// [0, 1] and still rewards genuine multi-family confirmation.
func (b *Bucket) confidenceStatsLocked(name string, strategies map[string]entity.Signal, now time.Time) (float64, float64) {
	const consensusBonus = 0.05

	peak := 0.0
	families := make(map[string]float64)
	for key, signal := range strategies {
		if signal.Expired(now) {
			delete(strategies, key)
			continue
		}
		if signal.Confidence > peak {
			peak = signal.Confidence
		}
		duration := float64(signal.Size.Duration().Milliseconds())
		decayed := util.ExponentialDecay(signal.Confidence, now, signal.Time, duration)
		family := signal.FamilyKey()
		if decayed > families[family] {
			families[family] = decayed
		}
	}
	if len(strategies) == 0 {
		delete(b.Signals, name)
		families = nil
	}

	total := 0.0
	for _, confidence := range families {
		if confidence > total {
			total = confidence
		}
	}
	if len(families) > 1 {
		total += consensusBonus * float64(len(families)-1)
	}
	if total > 1 {
		total = 1
	}
	if total < 0 {
		total = 0
	}
	return total, peak
}

// PeakConfidence returns the strongest raw confidence among live signals for a
// ticker.  Unlike GetConfidence it does not decay with age, which makes it the
// right comparison for "did a materially weaker signal replace our reason to
// hold?" checks.  A return of false means no live signal remains.
func (b *Bucket) PeakConfidence(name string) (float64, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	strategies, ok := b.Signals[name]
	if !ok {
		return 0, false
	}
	_, peak := b.confidenceStatsLocked(name, strategies, time.Now())
	return peak, peak > 0
}

// HasSignals reports whether the ticker still has at least one live signal.
func (b *Bucket) HasSignals(name string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	strategies, ok := b.Signals[name]
	if !ok {
		return false
	}
	now := time.Now()
	live := false
	for key, signal := range strategies {
		if signal.Expired(now) {
			delete(strategies, key)
			continue
		}
		live = true
	}
	if len(strategies) == 0 {
		delete(b.Signals, name)
	}
	return live
}

// GetTopNinBudget returns at most N tickers with the highest aggregate
// confidence whose current price is positive and affordable.
func (b *Bucket) GetTopNinBudget(n int, budget float64) []entity.SignalConf {
	if n <= 0 || budget <= 0 || b.Producer == nil {
		return nil
	}
	now := time.Now()

	b.mu.Lock()
	type candidate struct {
		name       string
		confidence float64
		peak       float64
	}
	candidates := make([]candidate, 0, len(b.Signals))
	for name, strategies := range b.Signals {
		conf, peak := b.confidenceStatsLocked(name, strategies, now)
		if conf > 0 {
			candidates = append(candidates, candidate{name: name, confidence: conf, peak: peak})
		}
	}
	b.mu.Unlock()

	heap := util.NewBoundedMaxHeap(n)
	for _, c := range candidates {
		if b.Producer == nil {
			continue
		}
		price := b.Producer.GetCurrentValue(c.name)
		if price <= 0 || price > budget {
			continue
		}
		heap.Push(entity.SignalConf{
			Name:           c.name,
			ResultantConf:  c.confidence,
			PeakConfidence: c.peak,
		})
	}
	return heap.GetSorted()
}

// PruneExpired drops stale signals and empty ticker entries.
func (b *Bucket) PruneExpired(now time.Time) {
	b.mu.Lock()
	defer b.mu.Unlock()
	for name, strategies := range b.Signals {
		for key, signal := range strategies {
			if signal.Expired(now) {
				delete(strategies, key)
			}
		}
		if len(strategies) == 0 {
			delete(b.Signals, name)
		}
	}
}

func (b *Bucket) Size() int {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return len(b.Signals)
}

// SignalState is an immutable per-ticker view used by the status API.
type SignalState struct {
	Name                string
	AggregateConfidence float64
	PeakConfidence      float64
	Signals             []entity.Signal
}

// Snapshot returns the live signal state for every ticker, strongest first.
// Expired signals are pruned as part of the snapshot.
func (b *Bucket) Snapshot() []SignalState {
	now := time.Now()
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make([]SignalState, 0, len(b.Signals))
	for name, strategies := range b.Signals {
		aggregate, peak := b.confidenceStatsLocked(name, strategies, now)
		signals := make([]entity.Signal, 0, len(strategies))
		for _, signal := range strategies {
			signals = append(signals, signal)
		}
		if len(signals) == 0 {
			continue
		}
		sort.Slice(signals, func(i, j int) bool {
			if signals[i].Strategy == signals[j].Strategy {
				return signals[i].Time.Before(signals[j].Time)
			}
			return signals[i].Strategy < signals[j].Strategy
		})
		out = append(out, SignalState{
			Name:                name,
			AggregateConfidence: aggregate,
			PeakConfidence:      peak,
			Signals:             signals,
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].AggregateConfidence == out[j].AggregateConfidence {
			return out[i].Name < out[j].Name
		}
		return out[i].AggregateConfidence > out[j].AggregateConfidence
	})
	return out
}

// Reset clears all signals (useful for tests and kill switches).
func (b *Bucket) Reset() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.Signals = make(map[string]map[string]entity.Signal)
}

// DebugString is used by diagnostics.
func (b *Bucket) DebugString() string {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return fmt.Sprintf("bucket{tickers=%d}", len(b.Signals))
}
