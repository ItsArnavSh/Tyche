package yahoo

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"gateway/app/util/entity"
	"gateway/app/util/workpool"
)

// Options configures the Yahoo Finance producer.
type Options struct {
	Symbols     []string
	BaseURL     string
	Suffix      string
	Timeout     time.Duration
	Retries     int
	BootCandles int
	BootWorkers int
	RollEvery   time.Duration
	HTTPClient  *http.Client
}

// Producer fetches real NSE market data from Yahoo Finance.
//
// History comes from the public /v8/finance/chart endpoint.  Rolling prices
// come from the batched /v8/finance/spark endpoint; requests are chunked so a
// large NIFTY 500 universe does not put hundreds of tickers in one URL.
type Producer struct {
	mu          sync.RWMutex
	symbols     []string
	baseURL     string
	suffix      string
	retries     int
	bootCandles int
	bootWorkers int
	rollEvery   time.Duration
	client      *http.Client
	latest      map[string]float64
	lastRoll    time.Time
	lastQuote   map[string]time.Time

	sparkFailures int
}

const (
	sparkBatchSize = 100
	// Per-symbol chart fallback is deliberately bounded. For a large universe
	// it is better to wait for the next batched spark tick than to fire one
	// HTTP request per ticker.
	quoteFallbackLimit = 100
)

func New(opts Options) *Producer {
	if opts.BaseURL == "" {
		opts.BaseURL = "https://query1.finance.yahoo.com"
	}
	if opts.Suffix == "" {
		opts.Suffix = ".NS"
	}
	if opts.Timeout <= 0 {
		opts.Timeout = 15 * time.Second
	}
	if opts.Retries <= 0 {
		opts.Retries = 3
	}
	if opts.BootCandles <= 0 {
		opts.BootCandles = 300
	}
	if opts.BootWorkers <= 0 {
		opts.BootWorkers = 4
	}
	if opts.RollEvery <= 0 {
		opts.RollEvery = 5 * time.Second
	}
	client := opts.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: opts.Timeout}
	}
	symbols := append([]string(nil), opts.Symbols...)
	if len(symbols) == 0 {
		symbols = []string{"RELIANCE.NS"}
	}
	return &Producer{
		symbols:     symbols,
		baseURL:     strings.TrimRight(opts.BaseURL, "/"),
		suffix:      opts.Suffix,
		retries:     opts.Retries,
		bootCandles: opts.BootCandles,
		bootWorkers: opts.BootWorkers,
		rollEvery:   opts.RollEvery,
		client:      client,
		latest:      make(map[string]float64),
		lastQuote:   make(map[string]time.Time),
	}
}

func (p *Producer) Symbols() []string {
	return append([]string(nil), p.symbols...)
}

func (p *Producer) RollInterval() time.Duration { return p.rollEvery }

func (p *Producer) Close() error { return nil }

func (p *Producer) GetCurrentValue(name string) float64 {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.latest[name]
}

func (p *Producer) BootRequest(req []entity.MonoCandle) []entity.TickerHistory {
	grouped := group(req)
	names := make([]string, 0, len(grouped))
	for name := range grouped {
		names = append(names, name)
	}
	sort.Strings(names)

	results := workpool.Map(names, p.bootWorkers, func(name string) []entity.TickerHistory {
		return p.bootSymbol(name, grouped[name])
	})
	out := make([]entity.TickerHistory, 0, len(names))
	for _, histories := range results {
		out = append(out, histories...)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Name != out[j].Name {
			return out[i].Name < out[j].Name
		}
		return out[i].History.Size < out[j].History.Size
	})
	return out
}

func (p *Producer) bootSymbol(name string, sizes []entity.CandleSize) []entity.TickerHistory {
	out := make([]entity.TickerHistory, 0, len(sizes))
	byInterval := make(map[string][]entity.CandleData)
	for _, size := range sizes {
		interval := intervalFor(size)
		candles, cached := byInterval[interval]
		if !cached {
			var err error
			candles, err = p.fetchChart(name, interval, rangeFor(interval))
			if err != nil {
				log.Printf("[yahoo] history %s %s failed: %v", name, interval, err)
				byInterval[interval] = nil
				continue
			}
			byInterval[interval] = candles
		}
		if len(candles) == 0 {
			continue
		}
		limit := p.bootCandles
		start := 0
		if len(candles) > limit {
			start = len(candles) - limit
		}
		series := entity.CandleSeries{Size: size, Data: append([]entity.CandleData(nil), candles[start:]...)}
		// TickerHistory carries exactly one series; emit one entry per size.
		out = append(out, entity.TickerHistory{Name: name, History: series})
	}
	return out
}

func (p *Producer) RollRequest(req []entity.MonoCandle) []entity.LatestVal {
	grouped := group(req)
	names := make([]string, 0, len(grouped))
	for name := range grouped {
		names = append(names, name)
	}
	sort.Strings(names)
	p.refreshPrices(names)

	out := make([]entity.LatestVal, 0)
	for _, name := range names {
		price := p.GetCurrentValue(name)
		if price <= 0 {
			continue
		}
		candle := entity.CandleData{Open: price, High: price, Low: price, Close: price, Volume: 0}
		for _, size := range grouped[name] {
			out = append(out, entity.LatestVal{
				Stock:  entity.MonoCandle{Name: name, Size: size},
				Candle: candle,
			})
		}
	}
	return out
}

func (p *Producer) setLatest(name string, price float64) {
	if price <= 0 {
		return
	}
	p.mu.Lock()
	p.latest[name] = price
	p.mu.Unlock()
}

func (p *Producer) refreshPrices(names []string) {
	if len(names) == 0 {
		return
	}
	p.mu.RLock()
	last := p.lastRoll
	failures := p.sparkFailures
	p.mu.RUnlock()
	// Avoid hammering Yahoo when several callers tick in quick succession.
	if time.Since(last) < p.rollEvery/2 {
		return
	}

	if failures < 3 {
		if err := p.fetchSpark(names); err == nil {
			p.mu.Lock()
			p.lastRoll = time.Now()
			p.sparkFailures = 0
			p.mu.Unlock()
			return
		} else {
			log.Printf("[yahoo] spark batch failed: %v", err)
			p.mu.Lock()
			p.sparkFailures++
			p.mu.Unlock()
		}
	}

	// Fallback: one chart request per symbol, individually rate limited. This is
	// only viable for a small watchlist; a 500-symbol run should keep retrying
	// the batched spark endpoint instead of issuing a request per ticker.
	if len(names) > quoteFallbackLimit {
		log.Printf("[yahoo] spark unavailable; skipping per-symbol fallback for %d symbols", len(names))
		p.mu.Lock()
		p.lastRoll = time.Now()
		p.mu.Unlock()
		return
	}
	for _, name := range names {
		p.mu.RLock()
		lastQuote := p.lastQuote[name]
		p.mu.RUnlock()
		if time.Since(lastQuote) < 10*time.Second {
			continue
		}
		candles, err := p.fetchChart(name, "1m", "1d")
		if err != nil {
			log.Printf("[yahoo] quote %s failed: %v", name, err)
			continue
		}
		if len(candles) > 0 {
			p.mu.Lock()
			p.lastQuote[name] = time.Now()
			p.mu.Unlock()
		}
	}
	p.mu.Lock()
	p.lastRoll = time.Now()
	p.mu.Unlock()
}

func (p *Producer) fetchSpark(names []string) error {
	for start := 0; start < len(names); start += sparkBatchSize {
		end := min(start+sparkBatchSize, len(names))
		if err := p.fetchSparkChunk(names[start:end]); err != nil {
			return err
		}
	}
	return nil
}

func (p *Producer) fetchSparkChunk(names []string) error {
	symbols := make([]string, 0, len(names))
	for _, name := range names {
		symbols = append(symbols, p.yahooSymbol(name))
	}
	endpoint := p.baseURL + "/v8/finance/spark?symbols=" +
		url.QueryEscape(strings.Join(symbols, ",")) +
		"&range=1d&interval=1m"
	body, err := p.get(endpoint)
	if err != nil {
		return err
	}
	var payload map[string]sparkSeries
	if err := json.Unmarshal(body, &payload); err != nil {
		return fmt.Errorf("decode spark: %w", err)
	}
	if len(payload) == 0 {
		return fmt.Errorf("spark returned no series")
	}
	for _, name := range names {
		series, ok := payload[p.yahooSymbol(name)]
		if !ok {
			series, ok = payload[name]
		}
		if !ok {
			continue
		}
		price := series.lastClose()
		if price <= 0 {
			price = series.FullDayPrice
		}
		if price > 0 {
			p.setLatest(name, price)
		}
	}
	return nil
}

type chartResponse struct {
	Chart struct {
		Result []struct {
			Meta struct {
				RegularMarketPrice float64 `json:"regularMarketPrice"`
			} `json:"meta"`
			Timestamp  []int64 `json:"timestamp"`
			Indicators struct {
				Quote []struct {
					Open   []*float64 `json:"open"`
					High   []*float64 `json:"high"`
					Low    []*float64 `json:"low"`
					Close  []*float64 `json:"close"`
					Volume []*float64 `json:"volume"`
				} `json:"quote"`
			} `json:"indicators"`
		} `json:"result"`
		Error *struct {
			Code        string `json:"code"`
			Description string `json:"description"`
		} `json:"error"`
	} `json:"chart"`
}

type sparkSeries struct {
	Timestamp    []int64    `json:"timestamp"`
	Close        []*float64 `json:"close"`
	FullDayPrice float64    `json:"fulldayPrice"`
}

func (s sparkSeries) lastClose() float64 {
	for i := len(s.Close) - 1; i >= 0; i-- {
		if s.Close[i] != nil && *s.Close[i] > 0 {
			return *s.Close[i]
		}
	}
	return 0
}

func (p *Producer) fetchChart(name, interval, chartRange string) ([]entity.CandleData, error) {
	symbol := url.PathEscape(p.yahooSymbol(name))
	endpoint := fmt.Sprintf("%s/v8/finance/chart/%s?interval=%s&range=%s&includePrePost=false&events=div%%2Csplits",
		p.baseURL, symbol, interval, chartRange)
	body, err := p.get(endpoint)
	if err != nil {
		return nil, err
	}
	var payload chartResponse
	if err := json.Unmarshal(body, &payload); err != nil {
		return nil, fmt.Errorf("decode chart: %w", err)
	}
	if payload.Chart.Error != nil {
		return nil, fmt.Errorf("yahoo error %s: %s", payload.Chart.Error.Code, payload.Chart.Error.Description)
	}
	if len(payload.Chart.Result) == 0 {
		return nil, fmt.Errorf("empty chart result")
	}
	result := payload.Chart.Result[0]
	if len(result.Indicators.Quote) == 0 {
		return nil, fmt.Errorf("chart has no quote data")
	}
	quote := result.Indicators.Quote[0]
	limit := minInt(len(result.Timestamp), len(quote.Close))
	candles := make([]entity.CandleData, 0, limit)
	for i := 0; i < limit; i++ {
		close := value(quote.Close, i)
		if close <= 0 {
			continue
		}
		open := fallbackValue(quote.Open, i, close)
		high := fallbackValue(quote.High, i, maxFloat(open, close))
		low := fallbackValue(quote.Low, i, minFloat(open, close))
		if high < low {
			high, low = low, high
		}
		candles = append(candles, entity.CandleData{
			Open:   open,
			High:   high,
			Low:    low,
			Close:  close,
			Volume: int64(value(quote.Volume, i)),
		})
	}
	if result.Meta.RegularMarketPrice > 0 {
		p.setLatest(name, result.Meta.RegularMarketPrice)
	} else if len(candles) > 0 {
		p.setLatest(name, candles[len(candles)-1].Close)
	}
	return candles, nil
}

func (p *Producer) get(endpoint string) ([]byte, error) {
	var lastErr error
	for attempt := 0; attempt < p.retries; attempt++ {
		req, err := http.NewRequest(http.MethodGet, endpoint, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Tyche/1.0")
		req.Header.Set("Accept", "application/json")
		resp, err := p.client.Do(req)
		if err != nil {
			lastErr = err
		} else {
			body, readErr := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
			resp.Body.Close()
			if readErr != nil {
				lastErr = readErr
			} else if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				return body, nil
			} else if resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode >= 500 {
				lastErr = fmt.Errorf("yahoo HTTP %d", resp.StatusCode)
			} else {
				return nil, fmt.Errorf("yahoo HTTP %d: %s", resp.StatusCode, truncate(string(body), 200))
			}
		}
		if attempt+1 < p.retries {
			time.Sleep(time.Duration(150*(attempt+1)) * time.Millisecond)
		}
	}
	return nil, lastErr
}

func (p *Producer) yahooSymbol(name string) string {
	symbol := strings.TrimSpace(name)
	if symbol == "" {
		return ""
	}
	if idx := strings.Index(symbol, ":"); idx >= 0 {
		symbol = symbol[idx+1:]
	}
	if strings.HasPrefix(symbol, "^") || strings.Contains(symbol, ".") || p.suffix == "" {
		return symbol
	}
	return symbol + p.suffix
}

func intervalFor(size entity.CandleSize) string {
	switch size {
	case entity.SEC5, entity.SEC30, entity.MIN1:
		return "1m"
	case entity.MIN15:
		return "15m"
	case entity.HOUR1:
		return "60m"
	default:
		return "1m"
	}
}

func rangeFor(interval string) string {
	switch interval {
	case "1m":
		return "7d"
	case "15m":
		return "1mo"
	case "60m":
		return "6mo"
	default:
		return "7d"
	}
}

func group(req []entity.MonoCandle) map[string][]entity.CandleSize {
	grouped := make(map[string][]entity.CandleSize)
	seen := make(map[string]map[entity.CandleSize]bool)
	for _, item := range req {
		if item.Name == "" {
			continue
		}
		if seen[item.Name] == nil {
			seen[item.Name] = make(map[entity.CandleSize]bool)
		}
		if seen[item.Name][item.Size] {
			continue
		}
		seen[item.Name][item.Size] = true
		grouped[item.Name] = append(grouped[item.Name], item.Size)
	}
	for _, sizes := range grouped {
		sort.Slice(sizes, func(i, j int) bool { return sizes[i] < sizes[j] })
	}
	return grouped
}

func value(series []*float64, index int) float64 {
	if index < 0 || index >= len(series) || series[index] == nil {
		return 0
	}
	return *series[index]
}

func fallbackValue(series []*float64, index int, fallback float64) float64 {
	if v := value(series, index); v > 0 {
		return v
	}
	return fallback
}

func maxFloat(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}

func minFloat(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func truncate(value string, n int) string {
	if len(value) <= n {
		return value
	}
	return value[:n]
}
