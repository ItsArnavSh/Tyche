package decision

import (
	"context"
	"fmt"
	"log"
	"sort"
	"sync"
	"time"

	"gateway/app/config"
	"gateway/app/core/monitor"
	"gateway/app/status"
	"gateway/app/util/bucket"
	"gateway/app/util/entity"
	"gateway/app/util/transaction"
)

// DecisionLayer turns decaying signals into sized, risk-bounded paper orders.
//
// It is the only component allowed to create BUY instructions.  The monitor is
// the only component allowed to create SELL instructions for open positions.
type DecisionLayer struct {
	transaction transaction.TransactionHandler
	bucket      *bucket.Bucket
	signalCh    <-chan entity.Signal
	monitor     *monitor.TradeMonitor

	settings config.DecisionSettings
	interval time.Duration
	recorder status.Recorder
	equity   func() float64

	mu      sync.Mutex
	lastBuy map[string]time.Time
}

// Options configures the layer.  Zero values fall back to environment-driven
// defaults, which keeps construction convenient for production and tests.
type Options struct {
	Settings config.DecisionSettings
	Interval time.Duration
	Recorder status.Recorder
	// Equity returns the current portfolio value (cash plus marked-to-market
	// holdings).  Sizing uses equity rather than remaining cash so a fully
	// invested account does not shrink every subsequent position.
	Equity func() float64
}

func NewDecisionLayer(
	th transaction.TransactionHandler,
	b *bucket.Bucket,
	sig <-chan entity.Signal,
	mon *monitor.TradeMonitor,
	options ...Options,
) *DecisionLayer {
	opts := Options{}
	if len(options) > 0 {
		opts = options[0]
	}
	if opts.Settings.MinConfidence == 0 {
		opts.Settings = config.Load().Decision
	}
	if opts.Interval <= 0 {
		opts.Interval = 5 * time.Second
	}
	return &DecisionLayer{
		transaction: th,
		bucket:      b,
		signalCh:    sig,
		monitor:     mon,
		settings:    opts.Settings,
		interval:    opts.Interval,
		recorder:    opts.Recorder,
		equity:      opts.Equity,
		lastBuy:     make(map[string]time.Time),
	}
}

// StartSignalPoller feeds incoming signals into the confidence bucket.
func (d *DecisionLayer) StartSignalPoller(ctx context.Context) {
	log.Println("[SignalPoller] started")
	for {
		select {
		case <-ctx.Done():
			log.Println("[SignalPoller] context cancelled — stopping")
			return
		case signal, ok := <-d.signalCh:
			if !ok {
				log.Println("[SignalPoller] signal channel closed — stopping")
				return
			}
			if d.recorder != nil {
				d.recorder.RecordSignal(signal)
			}
			d.bucket.SignalBucket(signal)
		}
	}
}

// StartDecisionMaking evaluates the bucket on a fixed cadence.
func (d *DecisionLayer) StartDecisionMaking(ctx context.Context) {
	log.Printf("[DecisionMaking] started (interval %s, minConfidence=%.2f, maxPositions=%d)",
		d.interval, d.settings.MinConfidence, d.settings.MaxPositions)
	ticker := time.NewTicker(d.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			d.tick()
		case <-ctx.Done():
			log.Println("[DecisionMaking] context cancelled — stopping")
			return
		}
	}
}

func (d *DecisionLayer) tick() {
	if d.bucket == nil || d.bucket.Producer == nil {
		return
	}
	d.bucket.PruneExpired(time.Now())

	budget, err := d.transaction.GetBudget()
	if err != nil {
		log.Printf("[DecisionMaking] budget lookup failed: %v", err)
		return
	}
	if budget <= 0 {
		return
	}

	availableSlots := d.settings.MaxPositions - d.monitor.ActivePositionCount()
	if availableSlots <= 0 {
		return
	}

	equity := budget
	if d.equity != nil {
		if value := d.equity(); value > 0 {
			equity = value
		}
	}

	choices := d.bucket.GetTopNinBudget(availableSlots*3, budget)
	if len(choices) == 0 {
		return
	}
	instructions, err := d.considerChoices(choices, budget, equity, availableSlots)
	if err != nil {
		log.Printf("[DecisionMaking] %v", err)
		return
	}
	if len(instructions) > 0 {
		d.BeginTransaction(instructions)
	}
}

// considerChoices applies confidence, cooldown, position and cash constraints.
// It is deterministic for a given bucket snapshot and is unit-tested directly.
func (d *DecisionLayer) considerChoices(
	choices []entity.SignalConf,
	budget float64,
	equity float64,
	availableSlots int,
) ([]entity.TransactionInstruction, error) {
	if availableSlots <= 0 {
		return nil, nil
	}
	maxPositions := d.settings.MaxPositions
	if maxPositions <= 0 {
		return nil, nil
	}
	qualified := make([]entity.SignalConf, 0, len(choices))
	for _, choice := range choices {
		if choice.ResultantConf < d.settings.MinConfidence {
			continue
		}
		if d.monitor.HasPosition(choice.Name) || d.inCooldown(choice.Name, time.Now()) {
			continue
		}
		qualified = append(qualified, choice)
	}
	if len(qualified) == 0 {
		return nil, nil
	}
	sort.Slice(qualified, func(i, j int) bool {
		if qualified[i].ResultantConf == qualified[j].ResultantConf {
			return qualified[i].Name < qualified[j].Name
		}
		return qualified[i].ResultantConf > qualified[j].ResultantConf
	})
	if len(qualified) > availableSlots {
		qualified = qualified[:availableSlots]
	}

	maxFraction := d.settings.MaxPositionFraction
	if maxFraction <= 0 || maxFraction > 1 {
		maxFraction = 0.15
	}
	reserve := d.settings.CashReserveFraction
	if reserve < 0 || reserve >= 1 {
		reserve = 0
	}
	if equity <= 0 {
		equity = budget
	}
	// Equal-weight target from portfolio equity, capped by the per-position
	// fraction.  Using remaining cash here would shrink every new position as
	// the account gets invested.  The reserve leaves room for fees/rounding.
	positionBudget := equity * (1 - reserve) / float64(maxPositions)
	if fractionCap := equity * maxFraction; positionBudget > fractionCap {
		positionBudget = fractionCap
	}
	if positionBudget <= 0 {
		return nil, nil
	}
	spendableCash := budget - equity*reserve
	if spendableCash <= 0 {
		return nil, nil
	}
	stopLoss := d.settings.StopLossPercent
	if stopLoss <= 0 || stopLoss >= 1 {
		stopLoss = 0.05
	}
	takeProfit := d.settings.TakeProfitPercent
	if takeProfit <= 0 {
		takeProfit = 0.10
	}
	hold := d.settings.HoldDuration
	if hold <= 0 {
		hold = 4 * time.Hour
	}

	now := time.Now()
	remainingCash := spendableCash
	instructions := make([]entity.TransactionInstruction, 0, len(qualified))
	for _, choice := range qualified {
		price := d.bucket.Producer.GetCurrentValue(choice.Name)
		if price <= 0 {
			continue
		}
		alloc := positionBudget
		if alloc > remainingCash {
			alloc = remainingCash
		}
		quantity := uint32(alloc / price)
		if quantity == 0 {
			continue
		}
		cost := float64(quantity) * price
		if cost > remainingCash {
			quantity = uint32(remainingCash / price)
			if quantity == 0 {
				continue
			}
			cost = float64(quantity) * price
		}

		entryConfidence := choice.PeakConfidence
		if entryConfidence <= 0 {
			entryConfidence = choice.ResultantConf
		}
		instructions = append(instructions, entity.TransactionInstruction{
			Ticker:     choice.Name,
			Action:     entity.BUY,
			Quantity:   quantity,
			Price:      price,
			Confidence: entryConfidence,
			Reason:     fmt.Sprintf("aggregate confidence %.1f%%", choice.ResultantConf*100),
			MaxVal:     price * (1 + takeProfit),
			MinValSell: price * (1 - stopLoss),
			// Strip Go's monotonic reading so the hold deadline is wall-clock
			// based and still expires if the host suspends.
			SellAfter: now.Add(hold).Round(0),
			OpenedAt:  now.Round(0),
		})
		remainingCash -= cost
		if remainingCash <= 0 {
			break
		}
	}
	return instructions, nil
}

func (d *DecisionLayer) inCooldown(ticker string, now time.Time) bool {
	if d.settings.RebuyCooldown <= 0 {
		return false
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	last, ok := d.lastBuy[ticker]
	return ok && now.Sub(last) < d.settings.RebuyCooldown
}

// BeginTransaction executes instructions in order and registers successful
// buys with the monitor.  Returns the number of filled orders.
func (d *DecisionLayer) BeginTransaction(insts []entity.TransactionInstruction) int {
	filled := 0
	for _, inst := range insts {
		if err := d.transaction.Process(inst); err != nil {
			if d.recorder != nil {
				d.recorder.RecordOrder(inst, "rejected", err)
			}
			log.Printf("[BeginTransaction] %s %s rejected: %v", inst.Action, inst.Ticker, err)
			continue
		}
		if d.recorder != nil {
			d.recorder.RecordOrder(inst, "filled", nil)
		}
		if inst.Action == entity.BUY {
			if !d.monitor.AddPosition(inst) {
				log.Printf("[BeginTransaction] %s filled but not registered (duplicate position)", inst.Ticker)
				continue
			}
			d.mu.Lock()
			d.lastBuy[inst.Ticker] = time.Now()
			d.mu.Unlock()
			filled++
			log.Printf("[BeginTransaction] BUY %s qty=%d @ %.4f conf=%.2f",
				inst.Ticker, inst.Quantity, inst.Price, inst.Confidence)
		}
	}
	return filled
}
