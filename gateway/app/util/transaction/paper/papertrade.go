package paper

import (
	"fmt"
	"math"
	"sort"
	"sync"

	"gateway/app/util/entity"
)

// Position is a long-only holding in the paper portfolio.
type Position struct {
	Ticker   string
	Quantity uint32
	AvgPrice float64
}

// Snapshot is an immutable view of the portfolio at a point in time.
type Snapshot struct {
	Cash        float64
	InitialCash float64
	Positions   []Position
	RealisedPnL float64
	FeesPaid    float64
	Buys        int
	Sells       int
	Wins        int
	Losses      int
}

type holding struct {
	quantity uint32
	avgPrice float64
}

// PaperTrade is a thread-safe, long-only paper-trading ledger.  It deliberately
// rejects orders that a real broker would reject (insufficient cash, selling
// more than held, non-positive prices) so bugs in the decision layer surface
// during simulation rather than in production.
type PaperTrade struct {
	mu          sync.RWMutex
	initialCash float64
	cash        float64
	feeRate     float64 // fraction of notional, e.g. 0.0002 = 2 bps
	holdings    map[string]*holding
	realisedPnL float64
	feesPaid    float64
	buys        int
	sells       int
	wins        int
	losses      int
}

var _ interface {
	GetBudget() (float64, error)
	Process(entity.TransactionInstruction) error
	ModifyBudget(entity.BudgetModification) (float64, error)
} = (*PaperTrade)(nil)

func NewPaperTrade(budget float64) *PaperTrade {
	return NewPaperTradeWithFees(budget, 0)
}

// NewPaperTradeWithFees creates a ledger with a proportional transaction fee in
// basis points (1 bps = 0.01%).
func NewPaperTradeWithFees(budget, feeBPS float64) *PaperTrade {
	if budget < 0 {
		budget = 0
	}
	if feeBPS < 0 {
		feeBPS = 0
	}
	return &PaperTrade{
		initialCash: budget,
		cash:        budget,
		feeRate:     feeBPS / 10_000,
		holdings:    make(map[string]*holding),
	}
}

func (p *PaperTrade) GetBudget() (float64, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.cash, nil
}

func (p *PaperTrade) Process(inst entity.TransactionInstruction) error {
	switch inst.Action {
	case entity.BUY:
		return p.buy(inst)
	case entity.SELL:
		return p.sell(inst)
	case entity.HOLD:
		return nil
	default:
		return fmt.Errorf("unsupported transaction action: %d", inst.Action)
	}
}

func (p *PaperTrade) buy(inst entity.TransactionInstruction) error {
	if inst.Ticker == "" {
		return fmt.Errorf("buy rejected: empty ticker")
	}
	if inst.Quantity == 0 {
		return fmt.Errorf("buy rejected for %s: quantity must be > 0", inst.Ticker)
	}
	if !validPrice(inst.Price) {
		return fmt.Errorf("buy rejected for %s: invalid price %.4f", inst.Ticker, inst.Price)
	}

	notional := float64(inst.Quantity) * inst.Price
	fee := notional * p.feeRate
	total := notional + fee

	p.mu.Lock()
	defer p.mu.Unlock()
	if total > p.cash+1e-9 {
		return fmt.Errorf("buy rejected for %s: need %.2f (incl. fee %.2f), cash %.2f",
			inst.Ticker, total, fee, p.cash)
	}

	position := p.holdings[inst.Ticker]
	if position == nil {
		position = &holding{}
		p.holdings[inst.Ticker] = position
	}
	newQuantity := uint64(position.quantity) + uint64(inst.Quantity)
	if newQuantity > math.MaxUint32 {
		delete(p.holdings, inst.Ticker)
		return fmt.Errorf("buy rejected for %s: position size overflow", inst.Ticker)
	}
	position.avgPrice = (position.avgPrice*float64(position.quantity) + notional) / float64(newQuantity)
	position.quantity = uint32(newQuantity)
	p.cash -= total
	p.feesPaid += fee
	p.buys++
	return nil
}

func (p *PaperTrade) sell(inst entity.TransactionInstruction) error {
	if inst.Ticker == "" {
		return fmt.Errorf("sell rejected: empty ticker")
	}
	if inst.Quantity == 0 {
		return fmt.Errorf("sell rejected for %s: quantity must be > 0", inst.Ticker)
	}
	if !validPrice(inst.Price) {
		return fmt.Errorf("sell rejected for %s: invalid price %.4f", inst.Ticker, inst.Price)
	}

	p.mu.Lock()
	defer p.mu.Unlock()
	position := p.holdings[inst.Ticker]
	if position == nil || position.quantity == 0 {
		return fmt.Errorf("sell rejected for %s: no open position", inst.Ticker)
	}
	if inst.Quantity > position.quantity {
		return fmt.Errorf("sell rejected for %s: holding %d, tried to sell %d",
			inst.Ticker, position.quantity, inst.Quantity)
	}

	notional := float64(inst.Quantity) * inst.Price
	fee := notional * p.feeRate
	p.cash += notional - fee
	p.feesPaid += fee

	pnl := (inst.Price-position.avgPrice)*float64(inst.Quantity) - fee
	p.realisedPnL += pnl
	if pnl >= 0 {
		p.wins++
	} else {
		p.losses++
	}
	p.sells++

	position.quantity -= inst.Quantity
	if position.quantity == 0 {
		delete(p.holdings, inst.Ticker)
	}
	return nil
}

func (p *PaperTrade) ModifyBudget(inst entity.BudgetModification) (float64, error) {
	if inst.Amount < 0 {
		return 0, fmt.Errorf("budget modification rejected: negative amount %.2f", inst.Amount)
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	switch inst.Action {
	case entity.ADD:
		p.cash += inst.Amount
		p.initialCash += inst.Amount
	case entity.REMOVE:
		p.cash -= inst.Amount
		if p.cash < 0 {
			p.cash = 0
		}
	default:
		return p.cash, fmt.Errorf("budget modification rejected: action must be ADD or REMOVE")
	}
	return p.cash, nil
}

// Position returns the current holding for ticker.
func (p *PaperTrade) Position(ticker string) (Position, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	h, ok := p.holdings[ticker]
	if !ok {
		return Position{}, false
	}
	return Position{Ticker: ticker, Quantity: h.quantity, AvgPrice: h.avgPrice}, true
}

// Snapshot returns a consistent copy of the ledger for reporting/tests.
func (p *PaperTrade) Snapshot() Snapshot {
	p.mu.RLock()
	defer p.mu.RUnlock()
	positions := make([]Position, 0, len(p.holdings))
	for ticker, h := range p.holdings {
		positions = append(positions, Position{Ticker: ticker, Quantity: h.quantity, AvgPrice: h.avgPrice})
	}
	sort.Slice(positions, func(i, j int) bool { return positions[i].Ticker < positions[j].Ticker })
	return Snapshot{
		Cash:        p.cash,
		InitialCash: p.initialCash,
		Positions:   positions,
		RealisedPnL: p.realisedPnL,
		FeesPaid:    p.feesPaid,
		Buys:        p.buys,
		Sells:       p.sells,
		Wins:        p.wins,
		Losses:      p.losses,
	}
}

// Reset restores the ledger to its initial cash with no positions.
func (p *PaperTrade) Reset() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.cash = p.initialCash
	p.holdings = make(map[string]*holding)
	p.realisedPnL = 0
	p.feesPaid = 0
	p.buys, p.sells, p.wins, p.losses = 0, 0, 0, 0
}

func validPrice(price float64) bool {
	return price > 0 && !math.IsNaN(price) && !math.IsInf(price, 0)
}
