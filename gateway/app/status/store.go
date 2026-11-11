// Package status owns the read-only operational view of the gateway.
//
// The gateway records decisions, fills, signals and feed health in memory and
// serves them as JSON. Nothing here talks to a broker or a market-data API;
// this is a lightweight, Go-owned status plane for the dashboard.
package status

import (
	"sync"
	"time"

	"gateway/app/util/entity"
)

const (
	maxSignals        = 200
	maxOrders         = 300
	maxClosedTrades   = 200
	maxErrors         = 50
	defaultMaxEntries = 200
)

// Recorder is the event sink used by the decision layer, monitor and market
// data pump. Implementations must be safe for concurrent use.
type Recorder interface {
	RecordSignal(entity.Signal)
	RecordOrder(entity.TransactionInstruction, string, error)
	RecordClosedTrade(ClosedTrade)
	RecordRoll(RollEvent)
	RecordError(scope string, err error)
}

// OrderEvent is one decision attempt: a paper buy or a monitor-driven sell.
type OrderEvent struct {
	ID         uint64    `json:"id"`
	At         time.Time `json:"at"`
	Ticker     string    `json:"ticker"`
	Action     string    `json:"action"`
	Quantity   uint32    `json:"quantity"`
	Price      float64   `json:"price"`
	Confidence float64   `json:"confidence"`
	Reason     string    `json:"reason"`
	Status     string    `json:"status"`
	Error      string    `json:"error,omitempty"`
}

// SignalEvent is one signal received from the Redis transport.
type SignalEvent struct {
	At         time.Time `json:"at"`
	Ticker     string    `json:"ticker"`
	Strategy   string    `json:"strategy"`
	Confidence float64   `json:"confidence"`
	Size       string    `json:"size"`
	ExpiresAt  time.Time `json:"expires_at"`
}

// ClosedTrade is a completed round trip recorded by the monitor.
type ClosedTrade struct {
	Ticker    string    `json:"ticker"`
	Quantity  uint32    `json:"quantity"`
	Entry     float64   `json:"entry"`
	Exit      float64   `json:"exit"`
	PnL       float64   `json:"pnl"`
	PnLPct    float64   `json:"pnl_pct"`
	Reason    string    `json:"reason"`
	OpenedAt  time.Time `json:"opened_at"`
	ClosedAt  time.Time `json:"closed_at"`
	DurationS int64     `json:"duration_seconds"`
}

// RollEvent describes one market-data roll cycle.
type RollEvent struct {
	At       time.Time
	Updates  int
	Missing  int
	Tickers  []string
	Boot     bool
	Failed   bool
	ErrorMsg string
}

// ErrorEvent is a bounded diagnostic entry.
type ErrorEvent struct {
	At    time.Time `json:"at"`
	Scope string    `json:"scope"`
	Error string    `json:"error"`
}

// Counters are monotonic totals since process start.
type Counters struct {
	SignalsReceived int64 `json:"signals_received"`
	Orders          int64 `json:"orders"`
	Fills           int64 `json:"fills"`
	Rejections      int64 `json:"rejections"`
	ClosedTrades    int64 `json:"closed_trades"`
}

// Health is the latest observed state of the data path.
type Health struct {
	LastRollAt      time.Time
	LastBootAt      time.Time
	LastRollUpdates int
	Missing         int
	MissingTickers  []string
	LastRollError   string
	LastErrorScope  string
	LastError       string
	LastErrorAt     time.Time
}

// Activity is an immutable copy of the in-memory event log.
type Activity struct {
	Signals      []SignalEvent
	Orders       []OrderEvent
	ClosedTrades []ClosedTrade
	Errors       []ErrorEvent
	Counters     Counters
	Health       Health
}

// Store is a bounded, thread-safe operational event log.
type Store struct {
	mu          sync.Mutex
	startedAt   time.Time
	nextOrderID uint64
	activity    Activity
}

var _ Recorder = (*Store)(nil)

func NewStore() *Store {
	return &Store{
		startedAt: time.Now(),
		activity: Activity{
			Signals:      make([]SignalEvent, 0, maxSignals),
			Orders:       make([]OrderEvent, 0, maxOrders),
			ClosedTrades: make([]ClosedTrade, 0, maxClosedTrades),
			Errors:       make([]ErrorEvent, 0, maxErrors),
		},
	}
}

func (s *Store) StartedAt() time.Time {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.startedAt
}

func (s *Store) RecordSignal(signal entity.Signal) {
	at := signal.Time
	if at.IsZero() {
		at = time.Now()
	}
	strategy := signal.Strategy
	if strategy == "" {
		strategy = "unknown"
	}
	event := SignalEvent{
		At:         at,
		Ticker:     signal.Name,
		Strategy:   strategy,
		Confidence: signal.Confidence,
		Size:       signal.Size.String(),
		ExpiresAt:  signal.ExpiresAt(),
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.activity.Counters.SignalsReceived++
	s.activity.Signals = appendCapped(s.activity.Signals, event, maxSignals)
}

func (s *Store) RecordOrder(inst entity.TransactionInstruction, result string, err error) {
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()

	s.nextOrderID++
	s.activity.Counters.Orders++
	if result == "filled" {
		s.activity.Counters.Fills++
	}
	if err != nil {
		s.activity.Counters.Rejections++
	}
	event := OrderEvent{
		ID:         s.nextOrderID,
		At:         now,
		Ticker:     inst.Ticker,
		Action:     inst.Action.String(),
		Quantity:   inst.Quantity,
		Price:      inst.Price,
		Confidence: inst.Confidence,
		Reason:     inst.Reason,
		Status:     result,
	}
	if err != nil {
		event.Error = err.Error()
	}
	s.activity.Orders = appendCapped(s.activity.Orders, event, maxOrders)
}

func (s *Store) RecordClosedTrade(trade ClosedTrade) {
	if trade.ClosedAt.IsZero() {
		trade.ClosedAt = time.Now()
	}
	if !trade.OpenedAt.IsZero() {
		trade.DurationS = int64(trade.ClosedAt.Sub(trade.OpenedAt).Seconds())
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.activity.Counters.ClosedTrades++
	s.activity.ClosedTrades = appendCapped(s.activity.ClosedTrades, trade, maxClosedTrades)
}

func (s *Store) RecordRoll(roll RollEvent) {
	at := roll.At
	if at.IsZero() {
		at = time.Now()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if roll.Boot {
		s.activity.Health.LastBootAt = at
	} else {
		s.activity.Health.LastRollAt = at
		s.activity.Health.LastRollUpdates = roll.Updates
		s.activity.Health.Missing = roll.Missing
		s.activity.Health.MissingTickers = append([]string{}, roll.Tickers...)
	}
	if roll.Failed {
		s.activity.Health.LastRollError = roll.ErrorMsg
	} else if !roll.Boot {
		s.activity.Health.LastRollError = ""
	}
}

func (s *Store) RecordError(scope string, err error) {
	if err == nil {
		return
	}
	now := time.Now()
	event := ErrorEvent{At: now, Scope: scope, Error: err.Error()}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.activity.Health.LastErrorScope = scope
	s.activity.Health.LastError = err.Error()
	s.activity.Health.LastErrorAt = now
	s.activity.Errors = appendCapped(s.activity.Errors, event, maxErrors)
}

// Activity returns a newest-first copy of the event log and counters.
func (s *Store) Activity() Activity {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := Activity{
		Signals:      newestFirst(s.activity.Signals),
		Orders:       newestFirst(s.activity.Orders),
		ClosedTrades: newestFirst(s.activity.ClosedTrades),
		Errors:       newestFirst(s.activity.Errors),
		Counters:     s.activity.Counters,
		Health:       s.activity.Health,
	}
	out.Health.MissingTickers = append([]string{}, s.activity.Health.MissingTickers...)
	return out
}

func appendCapped[T any](items []T, item T, limit int) []T {
	if limit <= 0 {
		limit = defaultMaxEntries
	}
	if len(items) < limit {
		return append(items, item)
	}
	copy(items, items[1:])
	items[len(items)-1] = item
	return items
}

func newestFirst[T any](items []T) []T {
	out := make([]T, len(items))
	for i := range items {
		out[i] = items[len(items)-1-i]
	}
	return out
}
