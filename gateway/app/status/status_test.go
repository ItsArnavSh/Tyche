package status

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"gateway/app/util/entity"
)

func TestStoreRecordsAndCapsActivity(t *testing.T) {
	store := NewStore()
	for i := 0; i < maxSignals+5; i++ {
		store.RecordSignal(entity.Signal{
			Name:       "TCS.NS",
			Strategy:   "rsi",
			Size:       entity.MIN1,
			Confidence: 0.8,
			Time:       time.Now().Add(time.Duration(i) * time.Millisecond),
		})
	}
	for i := 0; i < 4; i++ {
		store.RecordOrder(entity.TransactionInstruction{
			Ticker: "TCS.NS", Action: entity.BUY, Quantity: 2, Price: 100,
		}, "filled", nil)
	}
	store.RecordOrder(entity.TransactionInstruction{
		Ticker: "INFY.NS", Action: entity.BUY, Quantity: 1, Price: 200,
	}, "rejected", errors.New("not enough cash"))
	store.RecordError("roll", errors.New("quote unavailable"))

	activity := store.Activity()
	if len(activity.Signals) != maxSignals {
		t.Fatalf("signals = %d, want %d", len(activity.Signals), maxSignals)
	}
	if activity.Counters.SignalsReceived != int64(maxSignals+5) {
		t.Fatalf("signal counter = %d", activity.Counters.SignalsReceived)
	}
	if len(activity.Orders) != 5 || activity.Orders[0].Ticker != "INFY.NS" {
		t.Fatalf("orders are not newest-first: %+v", activity.Orders)
	}
	if activity.Counters.Fills != 4 || activity.Counters.Rejections != 1 {
		t.Fatalf("counters = %+v", activity.Counters)
	}
	if activity.Health.LastErrorScope != "roll" || activity.Health.LastError == "" {
		t.Fatalf("health = %+v", activity.Health)
	}

	activity.Orders[0].Ticker = "mutated"
	if store.Activity().Orders[0].Ticker != "INFY.NS" {
		t.Fatal("activity returned aliased storage")
	}
}

func TestStoreClosedTradeComputesDuration(t *testing.T) {
	store := NewStore()
	opened := time.Now().Add(-90 * time.Second)
	store.RecordClosedTrade(ClosedTrade{
		Ticker: "TCS.NS", Quantity: 1, Entry: 100, Exit: 110, PnL: 10,
		OpenedAt: opened, ClosedAt: time.Now(),
	})
	activity := store.Activity()
	if len(activity.ClosedTrades) != 1 {
		t.Fatalf("closed = %d", len(activity.ClosedTrades))
	}
	if activity.ClosedTrades[0].DurationS < 89 {
		t.Fatalf("duration = %d", activity.ClosedTrades[0].DurationS)
	}
	if activity.Counters.ClosedTrades != 1 {
		t.Fatalf("closed counter = %d", activity.Counters.ClosedTrades)
	}
}

func TestStoreRollHealth(t *testing.T) {
	store := NewStore()
	store.RecordRoll(RollEvent{At: time.Now(), Updates: 50, Missing: 2, Tickers: []string{"A", "B"}})
	store.RecordRoll(RollEvent{At: time.Now(), Updates: 10, Boot: true})
	activity := store.Activity()
	if activity.Health.LastRollUpdates != 50 || activity.Health.Missing != 2 {
		t.Fatalf("roll health = %+v", activity.Health)
	}
	if len(activity.Health.MissingTickers) != 2 || activity.Health.LastBootAt.IsZero() {
		t.Fatalf("roll details = %+v", activity.Health)
	}

	store.RecordRoll(RollEvent{At: time.Now(), Updates: 50})
	activity = store.Activity()
	if activity.Health.MissingTickers == nil || len(activity.Health.MissingTickers) != 0 {
		t.Fatalf("empty missing list = %#v", activity.Health.MissingTickers)
	}
}

func TestHandlerServesSnapshotAndCORS(t *testing.T) {
	now := time.Now()
	handler := Handler(func() Snapshot {
		return Snapshot{
			GeneratedAt: now,
			Mode:        "paper",
			State:       "running",
			Portfolio:   Portfolio{Equity: 101_000, Cash: 90_000},
		}
	})

	request := httptest.NewRequest(http.MethodGet, "/api/status", nil)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d", recorder.Code)
	}
	if got := recorder.Header().Get("Access-Control-Allow-Origin"); got != "*" {
		t.Fatalf("CORS = %q", got)
	}
	var snapshot Snapshot
	if err := json.Unmarshal(recorder.Body.Bytes(), &snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.Portfolio.Equity != 101_000 || snapshot.Mode != "paper" {
		t.Fatalf("snapshot = %+v", snapshot)
	}
}

func TestHandlerRejectsWrites(t *testing.T) {
	handler := Handler(func() Snapshot { return Snapshot{} })
	request := httptest.NewRequest(http.MethodPost, "/api/status", nil)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d", recorder.Code)
	}
}

func TestHandlerOptionsAndHealth(t *testing.T) {
	handler := Handler(func() Snapshot { return Snapshot{} })

	options := httptest.NewRecorder()
	handler.ServeHTTP(options, httptest.NewRequest(http.MethodOptions, "/api/status", nil))
	if options.Code != http.StatusNoContent {
		t.Fatalf("OPTIONS status = %d", options.Code)
	}

	health := httptest.NewRecorder()
	handler.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if health.Code != http.StatusOK {
		t.Fatalf("health status = %d", health.Code)
	}
}
