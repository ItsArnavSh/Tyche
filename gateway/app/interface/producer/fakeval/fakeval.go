package fakeval

import (
	"sync"
	"time"

	"gateway/app/util/entity"
	genutil "gateway/app/util/gen_util"
)

// DefaultSymbols is the synthetic universe used when no symbols are supplied.
var DefaultSymbols = []string{"A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10"}

// FakeValGen is a deterministic-ish random-walk data source used for demos and
// tests.  It is safe for concurrent BootRequest/RollRequest/GetCurrentValue.
type FakeValGen struct {
	mu            sync.RWMutex
	candleCount   int
	startPrice    float64
	symbols       []string
	interval      time.Duration
	currentPrices map[string]float64
}

func NewFakeValGen() *FakeValGen {
	return NewFakeValGenWithParams(100, 100.0)
}

func NewFakeValGenWithParams(candleCount int, startPrice float64) *FakeValGen {
	return NewFakeValGenWithSymbols(DefaultSymbols, candleCount, startPrice)
}

func NewFakeValGenWithSymbols(symbols []string, candleCount int, startPrice float64) *FakeValGen {
	if candleCount <= 0 {
		candleCount = 100
	}
	if startPrice <= 0 {
		startPrice = 100
	}
	copied := append([]string(nil), symbols...)
	if len(copied) == 0 {
		copied = append([]string(nil), DefaultSymbols...)
	}
	return &FakeValGen{
		candleCount:   candleCount,
		startPrice:    startPrice,
		symbols:       copied,
		interval:      5 * time.Second,
		currentPrices: make(map[string]float64),
	}
}

func (f *FakeValGen) Symbols() []string {
	return append([]string(nil), f.symbols...)
}

func (f *FakeValGen) RollInterval() time.Duration { return f.interval }

func (f *FakeValGen) Close() error { return nil }

func (f *FakeValGen) BootRequest(stocks []entity.MonoCandle) []entity.TickerHistory {
	grouped := group(stocks)
	out := make([]entity.TickerHistory, 0, len(grouped))

	f.mu.Lock()
	defer f.mu.Unlock()
	for ticker, sizes := range grouped {
		price := f.startPrice
		latest := price
		for _, size := range sizes {
			candles := make([]entity.CandleData, 0, f.candleCount)
			price = f.startPrice
			for i := 0; i < f.candleCount; i++ {
				candle := genutil.CandleGen(price)
				candles = append(candles, candle)
				price = candle.Close
			}
			out = append(out, entity.TickerHistory{
				Name:    ticker,
				History: entity.CandleSeries{Size: size, Data: candles},
			})
			latest = price
		}
		f.currentPrices[ticker] = latest
	}
	return out
}

func (f *FakeValGen) RollRequest(stocks []entity.MonoCandle) []entity.LatestVal {
	grouped := group(stocks)
	out := make([]entity.LatestVal, 0)
	f.mu.Lock()
	defer f.mu.Unlock()
	for ticker, sizes := range grouped {
		price, ok := f.currentPrices[ticker]
		if !ok || price <= 0 {
			price = f.startPrice
		}
		candle := genutil.CandleGen(price)
		f.currentPrices[ticker] = candle.Close
		for _, size := range sizes {
			out = append(out, entity.LatestVal{
				Stock:  entity.MonoCandle{Name: ticker, Size: size},
				Candle: candle,
			})
		}
	}
	return out
}

func (f *FakeValGen) GetCurrentValue(name string) float64 {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.currentPrices[name]
}

// SetPrice is useful in tests that need a precisely controlled price.
func (f *FakeValGen) SetPrice(name string, price float64) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.currentPrices[name] = price
}

func group(stocks []entity.MonoCandle) map[string][]entity.CandleSize {
	grouped := make(map[string][]entity.CandleSize)
	seen := make(map[string]map[entity.CandleSize]bool)
	for _, stock := range stocks {
		if stock.Name == "" {
			continue
		}
		if seen[stock.Name] == nil {
			seen[stock.Name] = make(map[entity.CandleSize]bool)
		}
		if seen[stock.Name][stock.Size] {
			continue
		}
		seen[stock.Name][stock.Size] = true
		grouped[stock.Name] = append(grouped[stock.Name], stock.Size)
	}
	// Stable size order makes boot payloads reproducible.
	for _, sizes := range grouped {
		for i := 1; i < len(sizes); i++ {
			for j := i; j > 0 && sizes[j] < sizes[j-1]; j-- {
				sizes[j], sizes[j-1] = sizes[j-1], sizes[j]
			}
		}
	}
	return grouped
}
