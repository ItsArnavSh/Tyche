package monitor

import (
	"context"
	"log"
	"sort"
	"sync"
	"time"

	"gateway/app/status"
	"gateway/app/util/bucket"
	"gateway/app/util/entity"
	"gateway/app/util/transaction"
)

// ClosedTrade records a completed round trip.
type ClosedTrade struct {
	Ticker   string
	Quantity uint32
	Entry    float64
	Exit     float64
	PnL      float64
	Reason   string
	OpenedAt time.Time
	ClosedAt time.Time
}

// Stats aggregates closed-trade performance.
type Stats struct {
	ClosedTrades int
	Wins         int
	Losses       int
	RealisedPnL  float64
}

// TradeMonitor watches open positions and exits them on stop-loss, take-profit,
// time limits, a material collapse in signal confidence, or a sustained loss of
// all live signals for the ticker.
//
// All exported methods are safe for concurrent use: the decision layer adds
// positions while the monitor goroutine evaluates and removes them.
type TradeMonitor struct {
	mu              sync.Mutex
	transaction     transaction.TransactionHandler
	bucket          *bucket.Bucket
	positions       map[string]entity.TransactionInstruction
	missingSince    map[string]time.Time
	closed          []ClosedTrade
	stats           Stats
	checkInterval   time.Duration
	signalLossGrace time.Duration
	now             func() time.Time
	recorder        status.Recorder
}

func NewTradeMonitor(th transaction.TransactionHandler, cb *bucket.Bucket, recorders ...status.Recorder) *TradeMonitor {
	return NewTradeMonitorWithInterval(th, cb, 5*time.Second, recorders...)
}

func NewTradeMonitorWithInterval(
	th transaction.TransactionHandler,
	cb *bucket.Bucket,
	interval time.Duration,
	recorders ...status.Recorder,
) *TradeMonitor {
	return NewTradeMonitorWithPolicy(th, cb, interval, 0, recorders...)
}

// NewTradeMonitorWithPolicy adds the signal-loss grace period.  A zero grace
// disables the signal-loss exit, which keeps the older constructors behaviour
// unchanged for callers that do not opt in.
func NewTradeMonitorWithPolicy(
	th transaction.TransactionHandler,
	cb *bucket.Bucket,
	interval time.Duration,
	signalLossGrace time.Duration,
	recorders ...status.Recorder,
) *TradeMonitor {
	if interval <= 0 {
		interval = 5 * time.Second
	}
	if signalLossGrace < 0 {
		signalLossGrace = 0
	}
	var recorder status.Recorder
	if len(recorders) > 0 {
		recorder = recorders[0]
	}
	return &TradeMonitor{
		transaction:     th,
		bucket:          cb,
		positions:       make(map[string]entity.TransactionInstruction),
		missingSince:    make(map[string]time.Time),
		checkInterval:   interval,
		signalLossGrace: signalLossGrace,
		now:             time.Now,
		recorder:        recorder,
	}
}

// AddPosition registers a filled buy.  A ticker can only have one active
// position, which keeps risk bounded and prevents duplicate instructions from
// stacking when several strategies agree.
func (m *TradeMonitor) AddPosition(position entity.TransactionInstruction) bool {
	if position.Ticker == "" || position.Quantity == 0 {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, exists := m.positions[position.Ticker]; exists {
		log.Printf("[TradeMonitor] ignoring duplicate position for %s", position.Ticker)
		return false
	}
	m.positions[position.Ticker] = position
	delete(m.missingSince, position.Ticker)
	log.Printf("[TradeMonitor] added position %s qty=%d price=%.4f target=%.4f stop=%.4f",
		position.Ticker, position.Quantity, position.Price, position.MaxVal, position.MinValSell)
	return true
}

func (m *TradeMonitor) HasPosition(ticker string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, exists := m.positions[ticker]
	return exists
}

func (m *TradeMonitor) ActivePositionCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.positions)
}

// GetActivePositionCount is kept for backwards compatibility.
func (m *TradeMonitor) GetActivePositionCount() int {
	return m.ActivePositionCount()
}

// ActivePositions returns a copy, sorted by ticker.
func (m *TradeMonitor) ActivePositions() []entity.TransactionInstruction {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]entity.TransactionInstruction, 0, len(m.positions))
	for _, position := range m.positions {
		out = append(out, position)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Ticker < out[j].Ticker })
	return out
}

func (m *TradeMonitor) Stats() Stats {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.stats
}

func (m *TradeMonitor) ClosedTrades() []ClosedTrade {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]ClosedTrade, len(m.closed))
	copy(out, m.closed)
	return out
}

func (m *TradeMonitor) StartMonitor(ctx context.Context) {
	log.Printf("[TradeMonitor] started (interval %s)", m.checkInterval)
	ticker := time.NewTicker(m.checkInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			m.CheckPositions()
		case <-ctx.Done():
			log.Println("[TradeMonitor] context cancelled — stopping")
			return
		}
	}
}

// CheckPositions is exported so tests can drive the monitor deterministically.
func (m *TradeMonitor) CheckPositions() {
	m.mu.Lock()
	positions := make([]entity.TransactionInstruction, 0, len(m.positions))
	for _, position := range m.positions {
		positions = append(positions, position)
	}
	m.mu.Unlock()

	sort.Slice(positions, func(i, j int) bool { return positions[i].Ticker < positions[j].Ticker })
	for _, position := range positions {
		if price, ok := m.currentPrice(position.Ticker); ok {
			if shouldSell, reason := m.shouldSell(position, price); shouldSell {
				m.sellPosition(position, price, reason)
			}
		}
	}
}

func (m *TradeMonitor) currentPrice(ticker string) (float64, bool) {
	if m.bucket == nil || m.bucket.Producer == nil {
		return 0, false
	}
	price := m.bucket.Producer.GetCurrentValue(ticker)
	if price <= 0 {
		return 0, false
	}
	return price, true
}

func (m *TradeMonitor) shouldSell(position entity.TransactionInstruction, currentPrice float64) (bool, string) {
	now := m.now()
	if position.MaxVal > 0 && currentPrice >= position.MaxVal {
		return true, "take profit target reached"
	}
	if position.MinValSell > 0 && currentPrice <= position.MinValSell {
		return true, "stop loss triggered"
	}
	// Compare wall-clock values: monotonic readings pause across host suspend,
	// which would let a position outlive its hold deadline after a long sleep.
	if !position.SellAfter.IsZero() && now.Round(0).After(position.SellAfter.Round(0)) {
		return true, "time limit exceeded"
	}
	// Compare against the strongest raw signal, not the time-decayed aggregate:
	// normal signal ageing must not force an exit.  A new, materially weaker
	// signal replacing the entry thesis still does.
	if m.bucket != nil {
		if peak, ok := m.bucket.PeakConfidence(position.Ticker); ok &&
			position.Confidence-peak > 0.20 {
			return true, "confidence dropped significantly"
		}
	}
	if m.signalLost(position.Ticker, now) {
		return true, "no live signal"
	}
	return false, ""
}

// signalLost reports whether every signal for ticker has been absent for at
// least the configured grace period.  The first observation only starts the
// timer; a position is never sold on the same check that the signal disappears.
func (m *TradeMonitor) signalLost(ticker string, now time.Time) bool {
	if m.signalLossGrace <= 0 || m.bucket == nil {
		return false
	}
	if m.bucket.HasSignals(ticker) {
		m.mu.Lock()
		delete(m.missingSince, ticker)
		m.mu.Unlock()
		return false
	}

	m.mu.Lock()
	since, seen := m.missingSince[ticker]
	if !seen {
		m.missingSince[ticker] = now
		m.mu.Unlock()
		return false
	}
	m.mu.Unlock()
	return now.Sub(since) >= m.signalLossGrace
}

func (m *TradeMonitor) sellPosition(position entity.TransactionInstruction, currentPrice float64, reason string) {
	sellInstruction := entity.TransactionInstruction{
		Ticker:   position.Ticker,
		Action:   entity.SELL,
		Quantity: position.Quantity,
		Price:    currentPrice,
		Reason:   reason,
	}
	if err := m.transaction.Process(sellInstruction); err != nil {
		if m.recorder != nil {
			m.recorder.RecordOrder(sellInstruction, "rejected", err)
		}
		// Keep the position: a rejected sell must not silently disappear.
		log.Printf("[TradeMonitor] sell failed for %s: %v", position.Ticker, err)
		return
	}
	if m.recorder != nil {
		m.recorder.RecordOrder(sellInstruction, "filled", nil)
	}

	buyValue := float64(position.Quantity) * position.Price
	sellValue := float64(position.Quantity) * currentPrice
	pnl := sellValue - buyValue
	pnlPct := 0.0
	if buyValue > 0 {
		pnlPct = pnl / buyValue * 100
	}

	m.mu.Lock()
	// Only remove the exact position we sold.  If a new position was opened in
	// the meantime, leave it alone.
	if active, ok := m.positions[position.Ticker]; ok && samePosition(active, position) {
		delete(m.positions, position.Ticker)
		delete(m.missingSince, position.Ticker)
		m.stats.ClosedTrades++
		m.stats.RealisedPnL += pnl
		if pnl >= 0 {
			m.stats.Wins++
		} else {
			m.stats.Losses++
		}
		closedAt := time.Now()
		trade := ClosedTrade{
			Ticker:   position.Ticker,
			Quantity: position.Quantity,
			Entry:    position.Price,
			Exit:     currentPrice,
			PnL:      pnl,
			Reason:   reason,
			OpenedAt: position.OpenedAt,
			ClosedAt: closedAt,
		}
		m.closed = append(m.closed, trade)
		if m.recorder != nil {
			pnlPct := 0.0
			if buyValue > 0 {
				pnlPct = pnl / buyValue * 100
			}
			duration := int64(0)
			if !trade.OpenedAt.IsZero() {
				duration = int64(trade.ClosedAt.Sub(trade.OpenedAt).Seconds())
			}
			m.recorder.RecordClosedTrade(status.ClosedTrade{
				Ticker:    trade.Ticker,
				Quantity:  trade.Quantity,
				Entry:     trade.Entry,
				Exit:      trade.Exit,
				PnL:       trade.PnL,
				PnLPct:    pnlPct,
				Reason:    trade.Reason,
				OpenedAt:  trade.OpenedAt,
				ClosedAt:  trade.ClosedAt,
				DurationS: duration,
			})
		}
	}
	m.mu.Unlock()

	log.Printf("[TradeMonitor] sold %s qty=%d entry=%.4f exit=%.4f P/L=%.2f (%.2f%%) reason=%s",
		position.Ticker, position.Quantity, position.Price, currentPrice, pnl, pnlPct, reason)
}

func samePosition(a, b entity.TransactionInstruction) bool {
	return a.Ticker == b.Ticker && a.Quantity == b.Quantity && a.Price == b.Price &&
		a.SellAfter.Equal(b.SellAfter) && a.OpenedAt.Equal(b.OpenedAt)
}

func (m *TradeMonitor) PrintSummary() {
	positions := m.ActivePositions()
	stats := m.Stats()
	log.Printf("[TradeMonitor] %d active position(s), %d closed trade(s), realised P&L %.2f",
		len(positions), stats.ClosedTrades, stats.RealisedPnL)
	for _, pos := range positions {
		currentPrice := 0.0
		if m.bucket != nil && m.bucket.Producer != nil {
			currentPrice = m.bucket.Producer.GetCurrentValue(pos.Ticker)
		}
		unrealised := (currentPrice - pos.Price) * float64(pos.Quantity)
		log.Printf("[TradeMonitor]   %s qty=%d entry=%.4f current=%.4f target=%.4f stop=%.4f unrealised=%.2f",
			pos.Ticker, pos.Quantity, pos.Price, currentPrice, pos.MaxVal, pos.MinValSell, unrealised)
	}
}
