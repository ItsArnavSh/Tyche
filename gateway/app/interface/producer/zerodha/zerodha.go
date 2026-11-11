package zerodha

import (
	"crypto/sha256"
	"encoding/csv"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"gateway/app/util/entity"
	"gateway/app/util/workpool"
)

// Options configures the Kite Connect producer.
type Options struct {
	Symbols     []string
	BaseURL     string
	APIKey      string
	APISecret   string
	AccessToken string
	Exchange    string
	Timeout     time.Duration
	Retries     int
	BootCandles int
	BootWorkers int
	RollEvery   time.Duration
	HTTPClient  *http.Client
}

// Instrument is the subset of Kite's instrument dump used by the gateway.
type Instrument struct {
	Token      uint32
	Symbol     string
	Exchange   string
	Instrument string
}

// Producer fetches real Kite Connect market data.  It resolves instrument
// tokens from the public instrument CSV, then uses the authenticated
// historical and quote/LTP endpoints.
type Producer struct {
	mu            sync.RWMutex
	rateMu        sync.Mutex
	symbols       []string
	baseURL       string
	apiKey        string
	apiSecret     string
	accessToken   string
	exchange      string
	retries       int
	bootCandles   int
	bootWorkers   int
	rollEvery     time.Duration
	client        *http.Client
	instruments   map[string]Instrument
	instrumentsAt time.Time
	latest        map[string]float64
	lastRequest   time.Time
}

const ltpBatchSize = 200

func New(opts Options) (*Producer, error) {
	if opts.APIKey == "" || opts.AccessToken == "" {
		return nil, fmt.Errorf("zerodha: TYCHE_KITE_API_KEY and TYCHE_KITE_ACCESS_TOKEN are required")
	}
	if opts.BaseURL == "" {
		opts.BaseURL = "https://api.kite.trade"
	}
	if opts.Exchange == "" {
		opts.Exchange = "NSE"
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
		symbols = []string{"RELIANCE"}
	}
	return &Producer{
		symbols:     symbols,
		baseURL:     strings.TrimRight(opts.BaseURL, "/"),
		apiKey:      opts.APIKey,
		apiSecret:   opts.APISecret,
		accessToken: opts.AccessToken,
		exchange:    strings.ToUpper(opts.Exchange),
		retries:     opts.Retries,
		bootCandles: opts.BootCandles,
		bootWorkers: opts.BootWorkers,
		rollEvery:   opts.RollEvery,
		client:      client,
		instruments: make(map[string]Instrument),
		latest:      make(map[string]float64),
	}, nil
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
	if err := p.ensureInstruments(); err != nil {
		log.Printf("[zerodha] instrument download failed: %v", err)
		return nil
	}
	grouped := group(req)
	names := make([]string, 0, len(grouped))
	for name := range grouped {
		names = append(names, name)
	}
	sort.Strings(names)

	results := workpool.Map(names, p.bootWorkers, func(name string) []entity.TickerHistory {
		instrument, ok := p.lookup(name)
		if !ok {
			log.Printf("[zerodha] unknown instrument %s", name)
			return nil
		}
		return p.bootSymbol(name, instrument, grouped[name])
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

func (p *Producer) bootSymbol(name string, instrument Instrument, sizes []entity.CandleSize) []entity.TickerHistory {
	out := make([]entity.TickerHistory, 0, len(sizes))
	byInterval := make(map[string][]entity.CandleData)
	for _, size := range sizes {
		interval := intervalFor(size)
		candles, cached := byInterval[interval]
		if !cached {
			var err error
			candles, err = p.fetchHistory(instrument, interval, rangeFor(interval))
			if err != nil {
				log.Printf("[zerodha] history %s %s failed: %v", name, interval, err)
				byInterval[interval] = nil
				continue
			}
			byInterval[interval] = candles
		}
		if len(candles) == 0 {
			continue
		}
		start := 0
		if len(candles) > p.bootCandles {
			start = len(candles) - p.bootCandles
		}
		series := entity.CandleSeries{Size: size, Data: append([]entity.CandleData(nil), candles[start:]...)}
		out = append(out, entity.TickerHistory{Name: name, History: series})
		if len(candles) > 0 {
			p.setLatest(name, candles[len(candles)-1].Close)
		}
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
		candle := entity.CandleData{Open: price, High: price, Low: price, Close: price}
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
	for start := 0; start < len(names); start += ltpBatchSize {
		end := min(start+ltpBatchSize, len(names))
		if err := p.refreshPricesChunk(names[start:end]); err != nil {
			log.Printf("[zerodha] LTP batch failed: %v", err)
			return
		}
	}
}

func (p *Producer) refreshPricesChunk(names []string) error {
	query := make([]string, 0, len(names))
	for _, name := range names {
		query = append(query, "i="+url.QueryEscape(p.quoteKey(name)))
	}
	endpoint := p.baseURL + "/quote/ltp?" + strings.Join(query, "&")
	body, err := p.get(endpoint)
	if err != nil {
		return err
	}
	var payload struct {
		Status string `json:"status"`
		Data   map[string]struct {
			LastPrice float64 `json:"last_price"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		return fmt.Errorf("decode LTP: %w", err)
	}
	for _, name := range names {
		key := p.quoteKey(name)
		if quote, ok := payload.Data[key]; ok && quote.LastPrice > 0 {
			p.setLatest(name, quote.LastPrice)
			continue
		}
		// Tolerate servers that echo only the trading symbol.
		for returned, quote := range payload.Data {
			if strings.EqualFold(strings.TrimPrefix(returned, p.exchange+":"), strings.TrimPrefix(key, p.exchange+":")) &&
				quote.LastPrice > 0 {
				p.setLatest(name, quote.LastPrice)
				break
			}
		}
	}
	return nil
}

func (p *Producer) quoteKey(name string) string {
	symbol := normalizeSymbol(name)
	return p.exchange + ":" + symbol
}

func (p *Producer) ensureInstruments() error {
	p.mu.RLock()
	fresh := len(p.instruments) > 0 && time.Since(p.instrumentsAt) < 24*time.Hour
	p.mu.RUnlock()
	if fresh {
		return nil
	}
	body, err := p.get(p.baseURL + "/instruments")
	if err != nil {
		return err
	}
	instruments, err := parseInstruments(strings.NewReader(string(body)))
	if err != nil {
		return err
	}
	p.mu.Lock()
	p.instruments = instruments
	p.instrumentsAt = time.Now()
	p.mu.Unlock()
	log.Printf("[zerodha] loaded %d instruments", len(instruments))
	return nil
}

func (p *Producer) lookup(name string) (Instrument, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	symbol := normalizeSymbol(name)
	if instrument, ok := p.instruments[p.exchange+":"+symbol]; ok {
		return instrument, true
	}
	for key, instrument := range p.instruments {
		if strings.EqualFold(strings.TrimPrefix(key, p.exchange+":"), symbol) {
			return instrument, true
		}
	}
	return Instrument{}, false
}

func (p *Producer) fetchHistory(instrument Instrument, interval string, historyRange time.Duration) ([]entity.CandleData, error) {
	now := time.Now().In(time.FixedZone("IST", 5*3600+1800))
	from := now.Add(-historyRange)
	endpoint := fmt.Sprintf("%s/instruments/historical/%d/%s?from=%s&to=%s",
		p.baseURL,
		instrument.Token,
		url.PathEscape(interval),
		url.QueryEscape(from.Format("2006-01-02 15:04:05")),
		url.QueryEscape(now.Format("2006-01-02 15:04:05")),
	)
	body, err := p.get(endpoint)
	if err != nil {
		return nil, err
	}
	var payload struct {
		Status string `json:"status"`
		Data   struct {
			Candles [][]json.RawMessage `json:"candles"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		return nil, fmt.Errorf("decode candles: %w", err)
	}
	candles := make([]entity.CandleData, 0, len(payload.Data.Candles))
	order := make([]time.Time, 0, len(payload.Data.Candles))
	for _, row := range payload.Data.Candles {
		if len(row) < 6 {
			continue
		}
		at, err := parseKiteTime(rawString(row[0]))
		if err != nil {
			continue
		}
		open := rawFloat(row[1])
		high := rawFloat(row[2])
		low := rawFloat(row[3])
		close := rawFloat(row[4])
		if close <= 0 {
			continue
		}
		if high < low {
			high, low = low, high
		}
		candles = append(candles, entity.CandleData{
			Open: open, High: high, Low: low, Close: close, Volume: int64(rawFloat(row[5])),
		})
		order = append(order, at)
	}
	// Kite normally returns oldest-first; sort defensively so the cache always
	// receives chronological data.
	sort.SliceStable(candles, func(i, j int) bool { return order[i].Before(order[j]) })
	return candles, nil
}

func (p *Producer) get(endpoint string) ([]byte, error) {
	var lastErr error
	for attempt := 0; attempt < p.retries; attempt++ {
		p.waitTurn()
		req, err := http.NewRequest(http.MethodGet, endpoint, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("X-Kite-Version", "3")
		req.Header.Set("Authorization", "token "+p.apiKey+":"+p.accessToken)
		req.Header.Set("Accept", "application/json")
		resp, err := p.client.Do(req)
		if err != nil {
			lastErr = err
		} else {
			body, readErr := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
			resp.Body.Close()
			if readErr != nil {
				lastErr = readErr
			} else if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				return body, nil
			} else if resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode >= 500 {
				lastErr = fmt.Errorf("kite HTTP %d", resp.StatusCode)
			} else {
				return nil, fmt.Errorf("kite HTTP %d: %s", resp.StatusCode, truncate(string(body), 200))
			}
		}
		if attempt+1 < p.retries {
			time.Sleep(time.Duration(200*(attempt+1)) * time.Millisecond)
		}
	}
	return nil, lastErr
}

// waitTurn keeps requests below Kite's 10 requests/second limit. It uses a
// dedicated mutex so concurrent history downloads serialise their request
// slots without blocking quote/cache reads on the state mutex.
func (p *Producer) waitTurn() {
	p.rateMu.Lock()
	defer p.rateMu.Unlock()
	if wait := 120*time.Millisecond - time.Since(p.lastRequest); wait > 0 {
		time.Sleep(wait)
	}
	p.lastRequest = time.Now()
}

// LoginURL is the one-time Kite Connect login URL used to obtain a request
// token. It is served by the gateway CLI, not by the Java core.
func LoginURL(apiKey string) string {
	return "https://kite.trade/connect/login?api_key=" + url.QueryEscape(apiKey) + "&v=3"
}

// ExchangeSession exchanges a request_token for an access_token. This is the
// operator-facing half of Kite authentication; it is intentionally kept in the
// Go data plane alongside the producer.
func ExchangeSession(baseURL, apiKey, apiSecret, requestToken string, timeout time.Duration) (string, error) {
	if baseURL == "" {
		baseURL = "https://api.kite.trade"
	}
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	return exchangeSession(&http.Client{Timeout: timeout}, baseURL, apiKey, apiSecret, requestToken)
}

// SessionToken exchanges a request_token for an access_token and stores it on
// the producer. Useful for the login callback flow; normal operation receives
// TYCHE_KITE_ACCESS_TOKEN from the environment.
func (p *Producer) SessionToken(requestToken string) (string, error) {
	token, err := exchangeSession(p.client, p.baseURL, p.apiKey, p.apiSecret, requestToken)
	if err != nil {
		return "", err
	}
	p.mu.Lock()
	p.accessToken = token
	p.mu.Unlock()
	return token, nil
}

func exchangeSession(client *http.Client, baseURL, apiKey, apiSecret, requestToken string) (string, error) {
	if apiKey == "" {
		return "", fmt.Errorf("zerodha: API key is required to generate a session")
	}
	if apiSecret == "" {
		return "", fmt.Errorf("zerodha: API secret is required to generate a session")
	}
	if requestToken == "" {
		return "", fmt.Errorf("zerodha: request token is required to generate a session")
	}
	baseURL = strings.TrimRight(baseURL, "/")
	sum := sha256.Sum256([]byte(apiKey + requestToken + apiSecret))
	form := url.Values{}
	form.Set("api_key", apiKey)
	form.Set("request_token", requestToken)
	form.Set("checksum", hex.EncodeToString(sum[:]))
	req, err := http.NewRequest(http.MethodPost, baseURL+"/session/token", strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("X-Kite-Version", "3")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("kite session HTTP %d: %s", resp.StatusCode, truncate(string(body), 200))
	}
	var payload struct {
		Data struct {
			AccessToken string `json:"access_token"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		return "", err
	}
	if payload.Data.AccessToken == "" {
		return "", fmt.Errorf("kite session response contained no access_token")
	}
	return payload.Data.AccessToken, nil
}

func parseInstruments(reader io.Reader) (map[string]Instrument, error) {
	csvReader := csv.NewReader(reader)
	csvReader.FieldsPerRecord = -1
	rows, err := csvReader.ReadAll()
	if err != nil {
		return nil, fmt.Errorf("parse instruments CSV: %w", err)
	}
	if len(rows) < 2 {
		return nil, fmt.Errorf("instruments CSV is empty")
	}
	header := make(map[string]int)
	for i, column := range rows[0] {
		header[strings.ToLower(strings.TrimSpace(column))] = i
	}
	column := func(row []string, name string) string {
		index, ok := header[name]
		if !ok || index >= len(row) {
			return ""
		}
		return strings.TrimSpace(row[index])
	}
	out := make(map[string]Instrument)
	for _, row := range rows[1:] {
		token, err := strconv.ParseUint(column(row, "instrument_token"), 10, 32)
		if err != nil {
			continue
		}
		symbol := column(row, "tradingsymbol")
		exchange := column(row, "exchange")
		if symbol == "" || exchange == "" {
			continue
		}
		out[strings.ToUpper(exchange)+":"+symbol] = Instrument{
			Token:      uint32(token),
			Symbol:     symbol,
			Exchange:   strings.ToUpper(exchange),
			Instrument: column(row, "instrument_type"),
		}
	}
	return out, nil
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

func normalizeSymbol(name string) string {
	symbol := strings.TrimSpace(name)
	if idx := strings.Index(symbol, ":"); idx >= 0 {
		symbol = symbol[idx+1:]
	}
	symbol = strings.TrimSuffix(strings.ToUpper(symbol), ".NS")
	return symbol
}

func intervalFor(size entity.CandleSize) string {
	switch size {
	case entity.SEC5, entity.SEC30, entity.MIN1:
		return "minute"
	case entity.MIN15:
		return "15minute"
	case entity.HOUR1:
		return "60minute"
	default:
		return "minute"
	}
}

func rangeFor(interval string) time.Duration {
	switch interval {
	case "minute":
		return 5 * 24 * time.Hour
	case "15minute":
		return 30 * 24 * time.Hour
	case "60minute":
		return 90 * 24 * time.Hour
	default:
		return 5 * 24 * time.Hour
	}
}

func rawString(raw json.RawMessage) string {
	var value string
	if err := json.Unmarshal(raw, &value); err == nil {
		return value
	}
	return string(raw)
}

func rawFloat(raw json.RawMessage) float64 {
	var value float64
	if err := json.Unmarshal(raw, &value); err == nil {
		return value
	}
	return 0
}

func parseKiteTime(value string) (time.Time, error) {
	for _, layout := range []string{time.RFC3339, "2006-01-02T15:04:05Z0700", "2006-01-02 15:04:05"} {
		if parsed, err := time.Parse(layout, value); err == nil {
			return parsed, nil
		}
	}
	return time.Time{}, fmt.Errorf("unrecognised timestamp %q", value)
}

func truncate(value string, n int) string {
	if len(value) <= n {
		return value
	}
	return value[:n]
}
