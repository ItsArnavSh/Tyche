package decision

import (
	"testing"
	"time"

	"gateway/app/config"
	"gateway/app/core/monitor"
	"gateway/app/interface/producer/fakeval"
	"gateway/app/util/bucket"
	"gateway/app/util/entity"
	"gateway/app/util/transaction/paper"
)

func setupDecision(t *testing.T, settings config.DecisionSettings) (*DecisionLayer, *bucket.Bucket, *paper.PaperTrade, *monitor.TradeMonitor, *fakeval.FakeValGen) {
	t.Helper()
	prod := fakeval.NewFakeValGenWithSymbols([]string{"A", "B", "C"}, 10, 100)
	prod.SetPrice("A", 100)
	prod.SetPrice("B", 200)
	prod.SetPrice("C", 50)
	b := bucket.NewBucket(prod)
	ledger := paper.NewPaperTrade(100_000)
	mon := monitor.NewTradeMonitor(ledger, b)
	signals := make(chan entity.Signal)
	d := NewDecisionLayer(ledger, b, signals, mon, Options{Settings: settings, Interval: time.Second})
	return d, b, ledger, mon, prod
}

func defaultSettings() config.DecisionSettings {
	return config.DecisionSettings{
		MinConfidence:       0.7,
		MaxPositions:        2,
		MaxPositionFraction: 0.2,
		StopLossPercent:     0.05,
		TakeProfitPercent:   0.10,
		HoldDuration:        time.Hour,
		RebuyCooldown:       time.Minute,
	}
}

func TestDecisionTickBuysQualifiedSignals(t *testing.T) {
	d, b, ledger, mon, _ := setupDecision(t, defaultSettings())
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.9})
	b.SignalBucket(entity.Signal{Name: "B", Strategy: "macd", Size: entity.HOUR1, Confidence: 0.8})
	b.SignalBucket(entity.Signal{Name: "C", Strategy: "sma", Size: entity.HOUR1, Confidence: 0.5})

	d.tick()

	if mon.ActivePositionCount() != 2 {
		t.Fatalf("active positions = %d", mon.ActivePositionCount())
	}
	positionA, ok := ledger.Position("A")
	if !ok || positionA.Quantity != 200 {
		t.Fatalf("A position = %+v ok=%v", positionA, ok)
	}
	positionB, ok := ledger.Position("B")
	if !ok || positionB.Quantity != 100 {
		t.Fatalf("B position = %+v ok=%v", positionB, ok)
	}
	if _, ok := ledger.Position("C"); ok {
		t.Fatal("low-confidence signal was traded")
	}
	cash, _ := ledger.GetBudget()
	if cash != 60_000 {
		t.Fatalf("cash = %.2f", cash)
	}

	// A second tick must not stack duplicate positions.
	d.tick()
	if mon.ActivePositionCount() != 2 {
		t.Fatalf("duplicate positions after second tick: %d", mon.ActivePositionCount())
	}
}

func TestDecisionRespectsExistingPositions(t *testing.T) {
	d, b, ledger, mon, _ := setupDecision(t, defaultSettings())
	existing := entity.TransactionInstruction{Ticker: "A", Action: entity.BUY, Quantity: 1, Price: 100}
	if err := ledger.Process(existing); err != nil {
		t.Fatal(err)
	}
	mon.AddPosition(existing)
	b.SignalBucket(entity.Signal{Name: "B", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.9})
	b.SignalBucket(entity.Signal{Name: "C", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.9})

	d.tick()
	if mon.ActivePositionCount() != 2 {
		t.Fatalf("active = %d, want existing + one new", mon.ActivePositionCount())
	}
	if _, ok := ledger.Position("B"); !ok {
		t.Fatal("B was not bought")
	}
	if _, ok := ledger.Position("C"); ok {
		t.Fatal("C exceeded max positions")
	}
}

func TestDecisionSkipsZeroPriceAndTinyAllocation(t *testing.T) {
	settings := defaultSettings()
	settings.MaxPositionFraction = 0.001 // 100 currency units
	d, b, ledger, mon, prod := setupDecision(t, settings)
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.9})
	b.SignalBucket(entity.Signal{Name: "B", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.9})
	prod.SetPrice("B", 0)

	d.tick()
	if mon.ActivePositionCount() != 1 {
		t.Fatalf("active = %d", mon.ActivePositionCount())
	}
	if _, ok := ledger.Position("B"); ok {
		t.Fatal("zero-price ticker was bought")
	}
	if p, ok := ledger.Position("A"); !ok || p.Quantity != 1 {
		t.Fatalf("A position = %+v", p)
	}
}

func TestBeginTransactionRejectedOrderNotRegistered(t *testing.T) {
	d, _, ledger, mon, _ := setupDecision(t, defaultSettings())
	received := d.BeginTransaction([]entity.TransactionInstruction{{
		Ticker: "A", Action: entity.BUY, Quantity: 10_000, Price: 100,
	}})
	if received != 0 {
		t.Fatalf("filled = %d", received)
	}
	if mon.ActivePositionCount() != 0 {
		t.Fatal("rejected order registered with monitor")
	}
	if cash, _ := ledger.GetBudget(); cash != 100_000 {
		t.Fatalf("cash changed: %.2f", cash)
	}
}

func TestCooldown(t *testing.T) {
	d, _, _, _, _ := setupDecision(t, defaultSettings())
	d.mu.Lock()
	d.lastBuy["A"] = time.Now()
	d.mu.Unlock()
	if !d.inCooldown("A", time.Now()) {
		t.Fatal("expected cooldown")
	}
	d.settings.RebuyCooldown = 0
	if d.inCooldown("A", time.Now()) {
		t.Fatal("zero cooldown should disable the check")
	}
}
