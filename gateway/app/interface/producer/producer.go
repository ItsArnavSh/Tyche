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
