package paper

import (
	"sync"
	"testing"

	"gateway/app/util/entity"
)

func TestPaperTradeBuySell(t *testing.T) {
	p := NewPaperTrade(1000)
	if err := p.Process(entity.TransactionInstruction{Ticker: "A", Action: entity.BUY, Quantity: 10, Price: 100}); err != nil {
		t.Fatal(err)
	}
	budget, _ := p.GetBudget()
	if budget != 0 {
		t.Fatalf("cash = %.2f", budget)
	}
	position, ok := p.Position("A")
	if !ok || position.Quantity != 10 || position.AvgPrice != 100 {
		t.Fatalf("position = %+v ok=%v", position, ok)
	}

	if err := p.Process(entity.TransactionInstruction{Ticker: "A", Action: entity.SELL, Quantity: 4, Price: 110}); err != nil {
		t.Fatal(err)
	}
	budget, _ = p.GetBudget()
	if budget != 440 {
		t.Fatalf("cash after partial sell = %.2f", budget)
	}
	position, _ = p.Position("A")
	if position.Quantity != 6 {
		t.Fatalf("remaining = %+v", position)
	}

	if err := p.Process(entity.TransactionInstruction{Ticker: "A", Action: entity.SELL, Quantity: 6, Price: 90}); err != nil {
		t.Fatal(err)
	}
	snapshot := p.Snapshot()
	if snapshot.RealisedPnL != -20 {
		t.Fatalf("realised = %.2f", snapshot.RealisedPnL)
	}
	if len(snapshot.Positions) != 0 {
		t.Fatalf("positions = %+v", snapshot.Positions)
	}
	if snapshot.Wins != 1 || snapshot.Losses != 1 {
		t.Fatalf("wins/losses = %d/%d", snapshot.Wins, snapshot.Losses)
	}
}

func TestPaperTradeRejectsInvalidOrders(t *testing.T) {
	p := NewPaperTrade(100)
	cases := []entity.TransactionInstruction{
		{Ticker: "A", Action: entity.BUY, Quantity: 0, Price: 10},
		{Ticker: "A", Action: entity.BUY, Quantity: 1, Price: 0},
		{Ticker: "A", Action: entity.BUY, Quantity: 11, Price: 10},
		{Ticker: "A", Action: entity.SELL, Quantity: 1, Price: 10},
		{Ticker: "", Action: entity.BUY, Quantity: 1, Price: 10},
	}
	for _, instruction := range cases {
		if err := p.Process(instruction); err == nil {
			t.Fatalf("expected rejection for %+v", instruction)
		}
	}
	if err := p.Process(entity.TransactionInstruction{Ticker: "A", Action: entity.HOLD}); err != nil {
		t.Fatalf("HOLD should be a no-op: %v", err)
	}
}

func TestPaperTradeFeesAndAveragePrice(t *testing.T) {
	p := NewPaperTradeWithFees(10_000, 10) // 10 bps = 0.1%
	if err := p.Process(entity.TransactionInstruction{Ticker: "A", Action: entity.BUY, Quantity: 10, Price: 100}); err != nil {
		t.Fatal(err)
	}
	if err := p.Process(entity.TransactionInstruction{Ticker: "A", Action: entity.BUY, Quantity: 10, Price: 200}); err != nil {
		t.Fatal(err)
	}
	position, _ := p.Position("A")
	if position.Quantity != 20 || position.AvgPrice != 150 {
		t.Fatalf("position = %+v", position)
	}
	snapshot := p.Snapshot()
	if snapshot.FeesPaid <= 0 {
		t.Fatalf("fees = %.4f", snapshot.FeesPaid)
	}
	if snapshot.Cash >= 10_000-3000 {
		t.Fatalf("fees were not deducted: cash=%.4f", snapshot.Cash)
	}
}

func TestPaperTradeBudgetModificationAndReset(t *testing.T) {
	p := NewPaperTrade(1000)
	if _, err := p.ModifyBudget(entity.BudgetModification{Action: entity.REMOVE, Amount: -1}); err == nil {
		t.Fatal("negative budget modification should fail")
	}
	remaining, err := p.ModifyBudget(entity.BudgetModification{Action: entity.ADD, Amount: 500})
	if err != nil || remaining != 1500 {
		t.Fatalf("remaining = %.2f err=%v", remaining, err)
	}
	remaining, _ = p.ModifyBudget(entity.BudgetModification{Action: entity.REMOVE, Amount: 100_000})
	if remaining != 0 {
		t.Fatalf("negative cash not clamped: %.2f", remaining)
	}
	if _, err := p.ModifyBudget(entity.BudgetModification{Action: entity.BUY, Amount: 1}); err == nil {
		t.Fatal("invalid budget action should fail")
	}
	p.Reset()
	snapshot := p.Snapshot()
	if snapshot.Cash != 1500 {
		t.Fatalf("reset cash = %.2f", snapshot.Cash)
	}
}

func TestPaperTradeConcurrentAccess(t *testing.T) {
	p := NewPaperTrade(1_000_000)
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = p.Process(entity.TransactionInstruction{Ticker: "A", Action: entity.BUY, Quantity: 1, Price: 10})
			_ = p.Process(entity.TransactionInstruction{Ticker: "A", Action: entity.SELL, Quantity: 1, Price: 11})
			_, _ = p.GetBudget()
			_ = p.Snapshot()
		}()
	}
	wg.Wait()
	snapshot := p.Snapshot()
	if len(snapshot.Positions) != 0 {
		t.Fatalf("positions after concurrent round trips = %+v", snapshot.Positions)
	}
	if snapshot.Cash != 1_000_000+50 {
		t.Fatalf("cash = %.2f", snapshot.Cash)
	}
}
