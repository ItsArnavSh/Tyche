package monitor

import (
	"errors"
	"testing"
	"time"

	"gateway/app/interface/producer/fakeval"
	"gateway/app/util/bucket"
	"gateway/app/util/entity"
	"gateway/app/util/transaction/paper"
)

func setupMonitor(t *testing.T) (*TradeMonitor, *paper.PaperTrade, *fakeval.FakeValGen, *bucket.Bucket) {
	t.Helper()
	prod := fakeval.NewFakeValGenWithSymbols([]string{"A", "B"}, 10, 100)
	prod.SetPrice("A", 100)
	prod.SetPrice("B", 200)
	b := bucket.NewBucket(prod)
	ledger := paper.NewPaperTrade(100_000)
	m := NewTradeMonitor(ledger, b)
	return m, ledger, prod, b
}

func setupSignalLossMonitor(t *testing.T, grace time.Duration) (*TradeMonitor, *paper.PaperTrade, *fakeval.FakeValGen, *bucket.Bucket) {
	t.Helper()
	prod := fakeval.NewFakeValGenWithSymbols([]string{"A"}, 10, 100)
	prod.SetPrice("A", 100)
	b := bucket.NewBucket(prod)
	ledger := paper.NewPaperTrade(100_000)
	m := NewTradeMonitorWithPolicy(ledger, b, 5*time.Second, grace)
	return m, ledger, prod, b
}

func buy(t *testing.T, ledger *paper.PaperTrade, instruction entity.TransactionInstruction) {
	t.Helper()
	if err := ledger.Process(instruction); err != nil {
		t.Fatal(err)
	}
}

func TestMonitorStopLossAndStats(t *testing.T) {
	m, ledger, prod, _ := setupMonitor(t)
	position := entity.TransactionInstruction{
		Ticker: "A", Action: entity.BUY, Quantity: 10, Price: 100,
		Confidence: 0.9, MaxVal: 110, MinValSell: 95,
		SellAfter: time.Now().Add(time.Hour),
	}
	buy(t, ledger, position)
	if !m.AddPosition(position) {
		t.Fatal("position was not added")
	}
	prod.SetPrice("A", 90)
	m.CheckPositions()
	if _, ok := ledger.Position("A"); ok {
		t.Fatal("position should have been sold")
	}
	if m.ActivePositionCount() != 0 {
		t.Fatalf("active = %d", m.ActivePositionCount())
	}
	stats := m.Stats()
	if stats.ClosedTrades != 1 || stats.Losses != 1 || stats.RealisedPnL != -100 {
		t.Fatalf("stats = %+v", stats)
	}
}

func TestMonitorTakeProfitAndTimeLimit(t *testing.T) {
	m, ledger, prod, _ := setupMonitor(t)
	take := entity.TransactionInstruction{
		Ticker: "A", Action: entity.BUY, Quantity: 5, Price: 100,
		MaxVal: 105, MinValSell: 90, SellAfter: time.Now().Add(time.Hour),
	}
	buy(t, ledger, take)
	m.AddPosition(take)
	prod.SetPrice("A", 106)
	m.CheckPositions()
	if m.ActivePositionCount() != 0 {
		t.Fatal("take profit did not exit")
	}

	timed := entity.TransactionInstruction{
		Ticker: "B", Action: entity.BUY, Quantity: 2, Price: 200,
		MaxVal: 300, MinValSell: 100, SellAfter: time.Now().Add(-time.Second),
	}
	buy(t, ledger, timed)
	m.AddPosition(timed)
	prod.SetPrice("B", 201)
	m.CheckPositions()
	if m.ActivePositionCount() != 0 {
		t.Fatal("time limit did not exit")
	}
	stats := m.Stats()
	if stats.ClosedTrades != 2 || stats.Wins != 2 {
		t.Fatalf("stats = %+v", stats)
	}
}

func TestMonitorDedupesPositions(t *testing.T) {
	m, ledger, _, _ := setupMonitor(t)
	position := entity.TransactionInstruction{Ticker: "A", Action: entity.BUY, Quantity: 1, Price: 100}
	buy(t, ledger, position)
	if !m.AddPosition(position) {
		t.Fatal("first add failed")
	}
	if m.AddPosition(position) {
		t.Fatal("duplicate add succeeded")
	}
	if m.ActivePositionCount() != 1 {
		t.Fatalf("active = %d", m.ActivePositionCount())
	}
}

func TestMonitorRetainsPositionWhenSellFails(t *testing.T) {
	prod := fakeval.NewFakeValGenWithSymbols([]string{"A"}, 10, 100)
	prod.SetPrice("A", 90)
	b := bucket.NewBucket(prod)
	m := NewTradeMonitor(rejectingHandler{}, b)
	position := entity.TransactionInstruction{
		Ticker: "A", Action: entity.BUY, Quantity: 10, Price: 100,
		MinValSell: 95, SellAfter: time.Now().Add(time.Hour),
	}
	m.AddPosition(position)
	m.CheckPositions()
	if m.ActivePositionCount() != 1 {
		t.Fatal("failed sell dropped the position")
	}
}

func TestMonitorConfidenceCollapse(t *testing.T) {
	m, ledger, prod, b := setupMonitor(t)
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.9})
	position := entity.TransactionInstruction{
		Ticker: "A", Action: entity.BUY, Quantity: 1, Price: 100,
		Confidence: 0.9, MinValSell: 10, SellAfter: time.Now().Add(time.Hour),
	}
	buy(t, ledger, position)
	m.AddPosition(position)
	prod.SetPrice("A", 100)
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.1})
	m.CheckPositions()
	if m.ActivePositionCount() != 0 {
		t.Fatal("confidence collapse did not exit")
	}
}

func TestMonitorDoesNotExitOnNormalSignalDecay(t *testing.T) {
	m, ledger, prod, b := setupMonitor(t)
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.SEC5,
		Confidence: 0.9, Time: time.Now().Add(-time.Second)})
	position := entity.TransactionInstruction{
		Ticker: "A", Action: entity.BUY, Quantity: 1, Price: 100,
		Confidence: 0.9, MinValSell: 10, SellAfter: time.Now().Add(time.Hour),
	}
	buy(t, ledger, position)
	m.AddPosition(position)
	prod.SetPrice("A", 100)

	m.CheckPositions()

	if m.ActivePositionCount() != 1 {
		t.Fatal("position exited because its signal aged")
	}
}

func TestMonitorIgnoresInvalidPrice(t *testing.T) {
	m, ledger, prod, _ := setupMonitor(t)
	position := entity.TransactionInstruction{
		Ticker: "A", Action: entity.BUY, Quantity: 1, Price: 100,
		MinValSell: 95, SellAfter: time.Now().Add(time.Hour),
	}
	buy(t, ledger, position)
	m.AddPosition(position)
	prod.SetPrice("A", 0)
	m.CheckPositions()
	if m.ActivePositionCount() != 1 {
		t.Fatal("position sold on invalid price")
	}
}

func TestMonitorExitsAfterSignalLossGrace(t *testing.T) {
	m, ledger, prod, _ := setupSignalLossMonitor(t, 5*time.Minute)
	now := time.Now()
	m.now = func() time.Time { return now }

	position := entity.TransactionInstruction{
		Ticker: "A", Action: entity.BUY, Quantity: 1, Price: 100,
		Confidence: 0.9, MinValSell: 10, SellAfter: now.Add(4 * time.Hour),
	}
	buy(t, ledger, position)
	m.AddPosition(position)
	prod.SetPrice("A", 100)

	m.CheckPositions() // starts the missing-signal timer
	if m.ActivePositionCount() != 1 {
		t.Fatal("position sold on the first missing-signal check")
	}

	now = now.Add(4 * time.Minute)
	m.CheckPositions()
	if m.ActivePositionCount() != 1 {
		t.Fatal("position sold before the signal-loss grace elapsed")
	}

	now = now.Add(2 * time.Minute)
	m.CheckPositions()
	if m.ActivePositionCount() != 0 {
		t.Fatal("position was not sold after the signal-loss grace")
	}
	trades := m.ClosedTrades()
	if len(trades) != 1 || trades[0].Reason != "no live signal" {
		t.Fatalf("closed trades = %+v", trades)
	}
}

func TestMonitorSignalLossTimerResetsWhenSignalReturns(t *testing.T) {
	m, ledger, prod, b := setupSignalLossMonitor(t, 5*time.Minute)
	now := time.Now()
	m.now = func() time.Time { return now }

	position := entity.TransactionInstruction{
		Ticker: "A", Action: entity.BUY, Quantity: 1, Price: 100,
		Confidence: 0.9, MinValSell: 10, SellAfter: now.Add(4 * time.Hour),
	}
	buy(t, ledger, position)
	m.AddPosition(position)
	prod.SetPrice("A", 100)

	m.CheckPositions() // starts the timer
	now = now.Add(4 * time.Minute)

	b.SignalBucket(entity.Signal{
		Name: "A", Strategy: "rsi", Size: entity.HOUR1,
		Confidence: 0.9, Time: now,
	})
	m.CheckPositions() // live signal resets the timer
	if m.ActivePositionCount() != 1 {
		t.Fatal("position sold while a live signal existed")
	}

	b.Reset()
	m.CheckPositions() // starts a fresh timer
	now = now.Add(4 * time.Minute)
	m.CheckPositions()
	if m.ActivePositionCount() != 1 {
		t.Fatal("position sold too soon after the timer restarted")
	}

	now = now.Add(2 * time.Minute)
	m.CheckPositions()
	if m.ActivePositionCount() != 0 {
		t.Fatal("position was not sold after the restarted grace elapsed")
	}
}

type rejectingHandler struct{}

func (rejectingHandler) GetBudget() (float64, error)                             { return 1000, nil }
func (rejectingHandler) ModifyBudget(entity.BudgetModification) (float64, error) { return 1000, nil }
func (rejectingHandler) Process(entity.TransactionInstruction) error {
	return errors.New("broker unavailable")
}
