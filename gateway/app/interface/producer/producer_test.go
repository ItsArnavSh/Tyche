package producer

import (
	"testing"

	"gateway/app/config"
	"gateway/app/interface/producer/fakeval"
	"gateway/app/util/entity"
)

func TestNewProducerSelectsSource(t *testing.T) {
	fake, err := NewProducer(config.Config{DataSource: "fake", Symbols: []string{"A", "B"}, BootCandleCount: 5})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := fake.(*fakeval.FakeValGen); !ok {
		t.Fatalf("fake type = %T", fake)
	}
	if len(fake.Symbols()) != 2 {
		t.Fatalf("symbols = %v", fake.Symbols())
	}
	if fake.RollInterval() <= 0 {
		t.Fatal("roll interval must be positive")
	}

	yahooProducer, err := NewProducer(config.Config{DataSource: "yahoo", Symbols: []string{"RELIANCE.NS"}})
	if err != nil {
		t.Fatal(err)
	}
	if got := yahooProducer.Symbols(); len(got) != 1 || got[0] != "RELIANCE.NS" {
		t.Fatalf("symbols = %v", got)
	}
	yahooProducer.Close()

	if _, err := NewProducer(config.Config{DataSource: "banana"}); err == nil {
		t.Fatal("unknown data source should fail")
	}
	if _, err := NewProducer(config.Config{DataSource: "zerodha"}); err == nil {
		t.Fatal("zerodha without credentials should fail")
	}
}

func TestBuildAndRemoveMonoCandles(t *testing.T) {
	candles := BuildAllMonoCandles([]string{"A", "B"})
	if len(candles) != 10 {
		t.Fatalf("candles = %d", len(candles))
	}
	remove := []entity.MonoCandle{{Name: "A", Size: entity.SEC5}, {Name: "B", Size: entity.HOUR1}}
	remaining := removeCandles(candles, remove)
	if len(remaining) != 8 {
		t.Fatalf("remaining = %d", len(remaining))
	}
	for _, candle := range remaining {
		if contains(remove, candle) {
			t.Fatalf("removed candle still present: %+v", candle)
		}
	}
	if !contains(candles, entity.MonoCandle{Name: "B", Size: entity.SEC30}) {
		t.Fatal("contains missed existing candle")
	}
	if contains(candles, entity.MonoCandle{Name: "C", Size: entity.SEC5}) {
		t.Fatal("contains found missing candle")
	}
}

func TestTradeInterfaceRequestBookkeeping(t *testing.T) {
	// removeCandles must not mutate its input slice.
	all := []entity.MonoCandle{{Name: "A", Size: entity.MIN1}, {Name: "B", Size: entity.MIN1}}
	_ = removeCandles(all, []entity.MonoCandle{{Name: "A", Size: entity.MIN1}})
	if len(all) != 2 {
		t.Fatalf("input mutated: %v", all)
	}
}

func TestChunkCandles(t *testing.T) {
	items := BuildAllMonoCandles([]string{"A", "B", "C"})
	chunks := chunkCandles(items, 5)
	if len(chunks) != 3 {
		t.Fatalf("chunks = %d", len(chunks))
	}
	for _, chunk := range chunks {
		if len(chunk) != 5 {
			t.Fatalf("chunk size = %d", len(chunk))
		}
	}
	if got := chunkCandles(nil, 5); got != nil {
		t.Fatalf("empty chunks = %v", got)
	}
	if got := chunkCandles(items, 0); len(got) != 1 || len(got[0]) != len(items) {
		t.Fatalf("zero-size chunks = %v", got)
	}
}
