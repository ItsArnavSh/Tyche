package server

import (
	"testing"
	"time"

	"gateway/app/config"
	"gateway/app/core/monitor"
	"gateway/app/interface/producer/fakeval"
	"gateway/app/status"
	"gateway/app/util/bucket"
	"gateway/app/util/entity"
	"gateway/app/util/transaction/paper"
)

func TestPortfolioEquityMarksHoldings(t *testing.T) {
	prod := fakeval.NewFakeValGenWithSymbols([]string{"A"}, 10, 100)
	prod.SetPrice("A", 110)
	ledger := paper.NewPaperTrade(100_000)
	if err := ledger.Process(entity.TransactionInstruction{
		Ticker: "A", Action: entity.BUY, Quantity: 10, Price: 100,
	}); err != nil {
		t.Fatal(err)
	}
	s := &Server{ledger: ledger, producer: prod}
	if got := s.portfolioEquity(); got != 100_100 {
		t.Fatalf("equity = %.2f, want 100,100", got)
	}

	prod.SetPrice("A", 0)
	if got := s.portfolioEquity(); got != 100_000 {
		t.Fatalf("fallback equity = %.2f, want 100,000", got)
	}
}

func TestSystemState(t *testing.T) {
	now := time.Now()
	running := status.NewStore()
	running.RecordRoll(status.RollEvent{At: now, Updates: 10, Boot: true})
	running.RecordRoll(status.RollEvent{At: now, Updates: 10})
	if got := systemState(running.Activity(), true, true); got != "running" {
		t.Fatalf("running state = %q", got)
	}

	starting := status.NewStore()
	starting.RecordRoll(status.RollEvent{At: now, Updates: 10, Boot: true})
	if got := systemState(starting.Activity(), true, true); got != "starting" {
		t.Fatalf("starting state = %q", got)
	}

	if got := systemState(running.Activity(), false, true); got != "degraded" {
		t.Fatalf("disconnected state = %q", got)
	}

	failed := status.NewStore()
	failed.RecordRoll(status.RollEvent{At: now, Failed: true, ErrorMsg: "quote failed"})
	if got := systemState(failed.Activity(), true, true); got != "degraded" {
		t.Fatalf("failed feed state = %q", got)
	}
}

func TestStatusSnapshotReflectsLedgerMonitorAndBucket(t *testing.T) {
	prod := fakeval.NewFakeValGenWithSymbols([]string{"A", "B"}, 10, 100)
	prod.SetPrice("A", 110)
	store := status.NewStore()
	ledger := paper.NewPaperTrade(100_000)
	b := bucket.NewBucket(prod)
	mon := monitor.NewTradeMonitor(ledger, b, store)

	position := entity.TransactionInstruction{
		Ticker: "A", Action: entity.BUY, Quantity: 10, Price: 100,
		Confidence: 0.9, MaxVal: 120, MinValSell: 90,
		OpenedAt: time.Now(), SellAfter: time.Now().Add(time.Hour),
	}
	if err := ledger.Process(position); err != nil {
		t.Fatal(err)
	}
	if !mon.AddPosition(position) {
		t.Fatal("position was not registered")
	}
	b.SignalBucket(entity.Signal{
		Name: "A", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.9, Time: time.Now(),
	})
	store.RecordSignal(entity.Signal{
		Name: "A", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.9, Time: time.Now(),
	})

	s := &Server{
		cfg: config.Config{
			DataSource: "fake",
			Symbols:    []string{"A", "B"},
			Decision: config.DecisionSettings{
				MinConfidence: 0.7, MaxPositions: 5, MaxPositionFraction: 0.15,
				CashReserveFraction: 0.02, StopLossPercent: 0.05, TakeProfitPercent: 0.1,
				HoldDuration: time.Hour, SignalLossGrace: 5 * time.Minute, RebuyCooldown: time.Minute,
			},
		},
		ledger:   ledger,
		bucket:   b,
		monitor:  mon,
		producer: prod,
		status:   store,
	}

	snapshot := s.StatusSnapshot()
	if snapshot.Mode != "paper" || snapshot.State != "degraded" {
		t.Fatalf("state = %+v", snapshot)
	}
	if snapshot.Portfolio.Cash != 99_000 || snapshot.Portfolio.MarketValue != 1_100 {
		t.Fatalf("portfolio = %+v", snapshot.Portfolio)
	}
	if snapshot.Portfolio.Equity != 100_100 || snapshot.Portfolio.TotalPnL != 100 {
		t.Fatalf("equity/pnl = %+v", snapshot.Portfolio)
	}
	if len(snapshot.Positions) != 1 || snapshot.Positions[0].CurrentPrice != 110 {
		t.Fatalf("positions = %+v", snapshot.Positions)
	}
	if snapshot.Positions[0].AggregateConfidence <= 0.8 {
		t.Fatalf("position confidence = %+v", snapshot.Positions[0])
	}
	if len(snapshot.Market) != 2 || snapshot.Market[0].Ticker != "A" || !snapshot.Market[0].HasPosition {
		t.Fatalf("market = %+v", snapshot.Market)
	}
	if snapshot.Feed.Source != "fake" || snapshot.Feed.RollIntervalMS <= 0 {
		t.Fatalf("feed = %+v", snapshot.Feed)
	}
	if snapshot.Stats.ActivePositions != 1 || snapshot.Stats.SignalsReceived != 1 {
		t.Fatalf("stats = %+v", snapshot.Stats)
	}
	if snapshot.Risk.CashReserveFraction != 0.02 {
		t.Fatalf("cash reserve = %v", snapshot.Risk.CashReserveFraction)
	}
	if snapshot.Risk.SignalLossGraceMS != (5 * time.Minute).Milliseconds() {
		t.Fatalf("signal loss grace = %d", snapshot.Risk.SignalLossGraceMS)
	}
}
