package yahoo

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"gateway/app/util/entity"
)

const chartPayload = `{
  "chart": {
    "result": [{
      "meta": {"regularMarketPrice": 105.5},
      "timestamp": [1, 2, 3],
      "indicators": {"quote": [{
        "open":   [100, 101, 102],
        "high":   [102, 103, 104],
        "low":    [99, 100, 101],
        "close":  [101, 102, null],
        "volume": [1000, 2000, 3000]
      }]}
    }],
    "error": null
  }
}`

func TestBootRequestParsesChart(t *testing.T) {
	var paths []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		if !strings.HasPrefix(r.URL.Path, "/v8/finance/chart/RELIANCE.NS") {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		switch r.URL.Query().Get("interval") {
		case "1m", "15m":
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, chartPayload)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	p := New(Options{
		Symbols:     []string{"RELIANCE.NS"},
		BaseURL:     server.URL,
		Suffix:      ".NS",
		BootCandles: 10,
		Retries:     1,
		Timeout:     2 * time.Second,
	})
	histories := p.BootRequest([]entity.MonoCandle{
		{Name: "RELIANCE", Size: entity.MIN1},
		{Name: "RELIANCE", Size: entity.MIN15},
	})
	if len(histories) != 2 {
		t.Fatalf("histories = %d (%v)", len(histories), paths)
	}
	for _, history := range histories {
		if history.Name != "RELIANCE" {
			t.Fatalf("name = %s", history.Name)
		}
		if len(history.History.Data) != 2 {
			t.Fatalf("candles = %d, want 2 (null close skipped)", len(history.History.Data))
		}
		candle := history.History.Data[0]
		if candle.Open != 100 || candle.High != 102 || candle.Low != 99 || candle.Close != 101 || candle.Volume != 1000 {
			t.Fatalf("candle = %+v", candle)
		}
	}
	if price := p.GetCurrentValue("RELIANCE"); price != 105.5 {
		t.Fatalf("latest = %v", price)
	}
}

func TestBootRequestClassifiesUnknownSymbolAsPermanent(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Query().Get("interval") {
		case "1m":
			http.NotFound(w, r)
		case "15m", "60m":
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, chartPayload)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	p := New(Options{
		Symbols:     []string{"DUMMY.NS"},
		BaseURL:     server.URL,
		BootCandles: 10,
		Retries:     1,
		Timeout:     2 * time.Second,
	})
	histories := p.BootRequest([]entity.MonoCandle{
		{Name: "DUMMY", Size: entity.SEC5},
		{Name: "DUMMY", Size: entity.SEC30},
		{Name: "DUMMY", Size: entity.MIN1},
		{Name: "DUMMY", Size: entity.MIN15},
		{Name: "DUMMY", Size: entity.HOUR1},
	})
	if len(histories) != 2 {
		t.Fatalf("histories = %d, want the 15m and 60m series", len(histories))
	}
	for _, size := range []entity.CandleSize{entity.SEC5, entity.SEC30, entity.MIN1} {
		if !p.PermanentBootFailure("DUMMY", size) {
			t.Fatalf("1m HTTP 404 was not classified as permanent for %s", size)
		}
	}
	if p.PermanentBootFailure("DUMMY", entity.MIN15) || p.PermanentBootFailure("DUMMY", entity.HOUR1) {
		t.Fatal("successful intervals were marked as permanent failures")
	}
}

func TestRollUsesBatchedSpark(t *testing.T) {
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.URL.Path != "/v8/finance/spark" {
			http.NotFound(w, r)
			return
		}
		if got := r.URL.Query().Get("symbols"); got != "RELIANCE.NS,TCS.NS" {
			t.Errorf("symbols = %q", got)
		}
		payload := map[string]any{
			"RELIANCE.NS": map[string]any{"close": []any{1310.0, 1320.5}, "fulldayPrice": 1320.5},
			"TCS.NS":      map[string]any{"close": []any{4100.0, nil}, "fulldayPrice": 4100.0},
		}
		json.NewEncoder(w).Encode(payload)
	}))
	defer server.Close()

	p := New(Options{Symbols: []string{"RELIANCE.NS", "TCS.NS"}, BaseURL: server.URL,
		Retries: 1, Timeout: 2 * time.Second, RollEvery: time.Second})
	updates := p.RollRequest([]entity.MonoCandle{
		{Name: "RELIANCE", Size: entity.MIN1},
		{Name: "RELIANCE", Size: entity.HOUR1},
		{Name: "TCS", Size: entity.MIN1},
	})
	if requests != 1 {
		t.Fatalf("requests = %d, want one batched spark call", requests)
	}
	if len(updates) != 3 {
		t.Fatalf("updates = %d", len(updates))
	}
	if p.GetCurrentValue("RELIANCE") != 1320.5 || p.GetCurrentValue("TCS") != 4100 {
		t.Fatalf("prices = %v / %v", p.GetCurrentValue("RELIANCE"), p.GetCurrentValue("TCS"))
	}
	for _, update := range updates {
		if update.Candle.Close != p.GetCurrentValue(update.Stock.Name) {
			t.Fatalf("update candle = %+v", update.Candle)
		}
	}
}

func TestRollChunksLargeSparkRequests(t *testing.T) {
	var batchSizes []int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v8/finance/spark" {
			http.NotFound(w, r)
			return
		}
		symbols := strings.Split(r.URL.Query().Get("symbols"), ",")
		batchSizes = append(batchSizes, len(symbols))
		payload := make(map[string]any, len(symbols))
		for _, symbol := range symbols {
			payload[symbol] = map[string]any{"close": []any{100.0}, "fulldayPrice": 100.0}
		}
		json.NewEncoder(w).Encode(payload)
	}))
	defer server.Close()

	p := New(Options{BaseURL: server.URL, Retries: 1, Timeout: 2 * time.Second, RollEvery: time.Second})
	req := make([]entity.MonoCandle, 0, sparkBatchSize+5)
	for i := 0; i < sparkBatchSize+5; i++ {
		req = append(req, entity.MonoCandle{Name: fmt.Sprintf("S%03d", i), Size: entity.MIN1})
	}
	updates := p.RollRequest(req)
	if len(batchSizes) != 2 || batchSizes[0] != sparkBatchSize || batchSizes[1] != 5 {
		t.Fatalf("batch sizes = %v", batchSizes)
	}
	if len(updates) != len(req) {
		t.Fatalf("updates = %d, want %d", len(updates), len(req))
	}
}

func TestRollFallsBackToChartWhenSparkFails(t *testing.T) {
	sparkCalls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/v8/finance/spark":
			sparkCalls++
			http.Error(w, "nope", http.StatusInternalServerError)
		case strings.HasPrefix(r.URL.Path, "/v8/finance/chart/"):
			fmt.Fprint(w, chartPayload)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	p := New(Options{Symbols: []string{"RELIANCE.NS"}, BaseURL: server.URL,
		Retries: 1, Timeout: 2 * time.Second, RollEvery: time.Second})
	updates := p.RollRequest([]entity.MonoCandle{{Name: "RELIANCE", Size: entity.MIN1}})
	if sparkCalls != 1 {
		t.Fatalf("spark calls = %d", sparkCalls)
	}
	if len(updates) != 1 {
		t.Fatalf("updates = %d", len(updates))
	}
	if p.GetCurrentValue("RELIANCE") != 105.5 {
		t.Fatalf("latest = %v", p.GetCurrentValue("RELIANCE"))
	}
}

func TestBootFailureIsNonFatal(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "down", http.StatusServiceUnavailable)
	}))
	defer server.Close()

	p := New(Options{Symbols: []string{"A"}, BaseURL: server.URL, Retries: 1,
		Timeout: time.Second, RollEvery: time.Second})
	if histories := p.BootRequest([]entity.MonoCandle{{Name: "A", Size: entity.MIN1}}); len(histories) != 0 {
		t.Fatalf("histories = %+v", histories)
	}
	if price := p.GetCurrentValue("A"); price != 0 {
		t.Fatalf("price = %v", price)
	}
}

func TestYahooSymbol(t *testing.T) {
	p := New(Options{Suffix: ".NS"})
	cases := map[string]string{
		"RELIANCE":    "RELIANCE.NS",
		"RELIANCE.NS": "RELIANCE.NS",
		"NSE:INFY":    "INFY.NS",
		"^NSEI":       "^NSEI",
		"":            "",
	}
	for input, want := range cases {
		if got := p.yahooSymbol(input); got != want {
			t.Fatalf("yahooSymbol(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestIntervalAndRangeMapping(t *testing.T) {
	if intervalFor(entity.SEC5) != "1m" || intervalFor(entity.MIN15) != "15m" || intervalFor(entity.HOUR1) != "60m" {
		t.Fatal("unexpected interval mapping")
	}
	if rangeFor("1m") != "7d" || rangeFor("15m") != "1mo" || rangeFor("60m") != "6mo" {
		t.Fatal("unexpected range mapping")
	}
}
