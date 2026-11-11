package producer

import (
	"fmt"
	"strings"
	"time"

	"gateway/app/config"
	"gateway/app/interface/producer/fakeval"
	"gateway/app/interface/producer/yahoo"
	"gateway/app/interface/producer/zerodha"
	"gateway/app/util/entity"
)

// Producer supplies historical and live market data to the gateway.
type Producer interface {
	BootRequest(req []entity.MonoCandle) []entity.TickerHistory
	RollRequest(req []entity.MonoCandle) []entity.LatestVal
	GetCurrentValue(name string) float64
	Symbols() []string
	RollInterval() time.Duration
	Close() error
}

// PermanentBootFailureReporter is an optional producer capability. Producers
// that can distinguish a delisted/unknown symbol from a transient HTTP or
// network failure implement it so the gateway can blacklist the series instead
// of retrying it forever.
type PermanentBootFailureReporter interface {
	PermanentBootFailure(name string, size entity.CandleSize) bool
}

var _ Producer = &fakeval.FakeValGen{}

// NewProducer selects the configured data source.
func NewProducer(cfg config.Config) (Producer, error) {
	switch strings.ToLower(strings.TrimSpace(cfg.DataSource)) {
	case "fake":
		return fakeval.NewFakeValGenWithSymbols(cfg.Symbols, cfg.BootCandleCount, 100), nil
	case "zerodha", "kite":
		return zerodha.New(zerodha.Options{
			Symbols:     cfg.Symbols,
			BaseURL:     cfg.KiteBaseURL,
			APIKey:      cfg.KiteAPIKey,
			APISecret:   cfg.KiteAPISecret,
			AccessToken: cfg.KiteAccessToken,
			Exchange:    cfg.KiteExchange,
			Timeout:     cfg.HTTPTimeout,
			Retries:     cfg.HTTPRetries,
			BootCandles: cfg.BootCandleCount,
			BootWorkers: cfg.BootWorkers,
			RollEvery:   cfg.RollInterval,
		})
	case "yahoo", "":
		return yahoo.New(yahoo.Options{
			Symbols:     cfg.Symbols,
			BaseURL:     cfg.YahooBaseURL,
			Suffix:      cfg.YahooSuffix,
			Timeout:     cfg.HTTPTimeout,
			Retries:     cfg.HTTPRetries,
			BootCandles: cfg.BootCandleCount,
			BootWorkers: cfg.BootWorkers,
			RollEvery:   cfg.RollInterval,
		}), nil
	default:
		return nil, fmt.Errorf("unknown TYCHE_DATA_SOURCE %q (expected fake, yahoo or zerodha)", cfg.DataSource)
	}
}
