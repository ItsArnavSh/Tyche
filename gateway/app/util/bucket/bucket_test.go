package bucket

import (
	"testing"
	"time"

	"gateway/app/interface/producer/fakeval"
	"gateway/app/util/entity"
)

func newTestBucket(prices map[string]float64) (*Bucket, *fakeval.FakeValGen) {
	names := make([]string, 0, len(prices))
	for name := range prices {
		names = append(names, name)
	}
	producer := fakeval.NewFakeValGenWithSymbols(names, 10, 100)
	for name, price := range prices {
		producer.SetPrice(name, price)
	}
	return NewBucket(producer), producer
}

func TestBucketDedupesPerStrategy(t *testing.T) {
	b, _ := newTestBucket(map[string]float64{"A": 100})
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.4})
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.HOUR1, Confidence: 0.8})
	if b.Size() != 1 {
		t.Fatalf("tickers = %d", b.Size())
	}
	confidence, err := b.GetConfidence("A")
	if err != nil {
		t.Fatal(err)
	}
	if confidence < 0.75 || confidence > 0.81 {
		t.Fatalf("confidence after replace = %.3f", confidence)
	}

	b.SignalBucket(entity.Signal{Name: "A", Strategy: "macd", Size: entity.HOUR1, Confidence: 0.5})
	confidence, _ = b.GetConfidence("A")
	if confidence < 0.99 {
		t.Fatalf("aggregate confidence = %.3f, want clamped to 1", confidence)
	}
}

func TestBucketRejectsExpiredAndInvalid(t *testing.T) {
	b, _ := newTestBucket(map[string]float64{"A": 100})
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.MIN1,
		Confidence: 0.9, Time: time.Now().Add(-2 * time.Minute)})
	if b.HasSignals("A") {
		t.Fatal("expired signal was stored")
	}
	b.SignalBucket(entity.Signal{Name: "", Strategy: "rsi", Size: entity.MIN1, Confidence: 0.9})
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.CandleSize(99), Confidence: 0.9})
	if b.Size() != 0 {
		t.Fatalf("invalid signals stored: %d", b.Size())
	}
}

func TestBucketConfidenceDecays(t *testing.T) {
	b, _ := newTestBucket(map[string]float64{"A": 100})
	start := time.Now().Add(-30 * time.Second)
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.MIN1,
		Confidence: 1, Time: start})
	confidence, _ := b.GetConfidence("A")
	if confidence <= 0.01 || confidence >= 0.9 {
		t.Fatalf("decayed confidence = %.4f", confidence)
	}
}

func TestBucketPeakConfidenceIgnoresDecay(t *testing.T) {
	b, _ := newTestBucket(map[string]float64{"A": 100})
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.MIN1,
		Confidence: 0.9, Time: time.Now().Add(-30 * time.Second)})

	peak, ok := b.PeakConfidence("A")
	if !ok || peak != 0.9 {
		t.Fatalf("peak = %.3f, ok = %v", peak, ok)
	}
	if _, ok := b.PeakConfidence("missing"); ok {
		t.Fatal("unknown ticker reported a peak")
	}

	b.SignalBucket(entity.Signal{Name: "A", Strategy: "macd", Size: entity.MIN1, Confidence: 0.7})
	peak, _ = b.PeakConfidence("A")
	if peak != 0.9 {
		t.Fatalf("peak should be max raw confidence, got %.3f", peak)
	}

	b.PruneExpired(time.Now().Add(2 * time.Minute))
	if _, ok := b.PeakConfidence("A"); ok {
		t.Fatal("expired signals still reported a peak")
	}
}

func TestBucketTopNRespectsBudgetAndOrder(t *testing.T) {
	b, _ := newTestBucket(map[string]float64{"A": 100, "B": 1000, "C": 50})
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "s", Size: entity.HOUR1, Confidence: 0.6})
	b.SignalBucket(entity.Signal{Name: "B", Strategy: "s", Size: entity.HOUR1, Confidence: 0.9})
	b.SignalBucket(entity.Signal{Name: "C", Strategy: "s", Size: entity.HOUR1, Confidence: 0.8})

	got := b.GetTopNinBudget(5, 500)
	if len(got) != 2 {
		t.Fatalf("choices = %+v", got)
	}
	if got[0].Name != "C" || got[1].Name != "A" {
		t.Fatalf("order = %+v", got)
	}
	if got[0].PeakConfidence != 0.8 || got[1].PeakConfidence != 0.6 {
		t.Fatalf("peak confidence not propagated: %+v", got)
	}
	if choices := b.GetTopNinBudget(1, 500); len(choices) != 1 || choices[0].Name != "C" {
		t.Fatalf("top1 = %+v", choices)
	}
	if choices := b.GetTopNinBudget(5, 0); choices != nil {
		t.Fatalf("zero budget should return nil, got %+v", choices)
	}
}

func TestBucketPercentageConfidenceAndPrune(t *testing.T) {
	b, _ := newTestBucket(map[string]float64{"A": 100})
	b.SignalBucket(entity.Signal{Name: "A", Strategy: "rsi", Size: entity.HOUR1, Confidence: 50})
	confidence, _ := b.GetConfidence("A")
	if confidence < 0.49 || confidence > 0.51 {
		t.Fatalf("percentage confidence = %.3f", confidence)
	}
	b.PruneExpired(time.Now().Add(2 * time.Hour))
	if b.Size() != 0 {
		t.Fatalf("prune left %d tickers", b.Size())
	}
}
