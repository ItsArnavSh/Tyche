// Package config centralises runtime configuration for the gateway.
//
// Every value can be overridden with an environment variable so the same
// binary works for local development, Docker Compose and production.
package config

import (
	"os"
	"strconv"
	"strings"
	"time"
)

// DecisionSettings controls the risk and sizing rules of the decision layer.
type DecisionSettings struct {
	MinConfidence       float64
	MaxPositions        int
	MaxPositionFraction float64
	StopLossPercent     float64
	TakeProfitPercent   float64
	HoldDuration        time.Duration
	RebuyCooldown       time.Duration
}

// Config is the fully resolved gateway configuration.
type Config struct {
	DataSource string
	// Symbols is an explicit TYCHE_SYMBOLS override. When empty, main resolves
	// the universe via the universe package before constructing the server.
	Symbols []string

	// Universe selects the automatic NSE index constituent list. Empty means
	// the default for real data sources; "off" keeps the fallback list.
	Universe      string
	UniverseURL   string
	UniverseLimit int
	// FallbackSymbols is used when the universe cannot be fetched.
	FallbackSymbols []string
	// UniverseSource is filled after resolution (env, nifty500, fallback, ...).
	UniverseSource string

	CoreAddr      string
	RedisAddr     string
	RedisPassword string
	RedisDB       int
	SignalQueue   string

	BootCandleCount int
	BootWorkers     int
	RollInterval    time.Duration
	DecisionEvery   time.Duration
	MonitorEvery    time.Duration

	YahooBaseURL string
	YahooSuffix  string
	HTTPTimeout  time.Duration
	HTTPRetries  int

	KiteBaseURL     string
	KiteAPIKey      string
	KiteAPISecret   string
	KiteAccessToken string
	KiteExchange    string

	InitialCash  float64
	PaperFeeBPS  float64
	StatusAddr   string
	Decision     DecisionSettings
	ShutdownWait time.Duration
}

// defaultSymbols is the fallback universe. Ticker selection lives in the Go
// data plane so the Java core never needs a symbol list.
const defaultSymbols = "RELIANCE.NS,TCS.NS,HDFCBANK.NS,INFY.NS,ICICIBANK.NS,SBIN.NS,ITC.NS,LT.NS,BHARTIARTL.NS,HINDUNILVR.NS"

// Default automatic universe for real market-data sources.
const DefaultUniverse = "nifty500"

// Load reads configuration from the process environment.
func Load() Config {
	dataSource := strings.ToLower(strings.TrimSpace(env("TYCHE_DATA_SOURCE", "yahoo")))

	// Fake mode stays offline by default unless the operator explicitly asks
	// for an automatic universe. Real sources default to the NIFTY 500 list.
	selectedUniverse := strings.ToLower(strings.TrimSpace(env("TYCHE_UNIVERSE", "")))
	if selectedUniverse == "" && dataSource != "fake" {
		selectedUniverse = DefaultUniverse
	}

	universeLimit := envInt("TYCHE_UNIVERSE_LIMIT", 0)
	if universeLimit < 0 {
		universeLimit = 0
	}
	bootWorkers := envInt("TYCHE_BOOT_WORKERS", 4)
	if bootWorkers < 1 {
		bootWorkers = 1
	}

	return Config{
		DataSource:      dataSource,
		Symbols:         splitCSV(env("TYCHE_SYMBOLS", "")),
		Universe:        selectedUniverse,
		UniverseURL:     env("TYCHE_UNIVERSE_URL", ""),
		UniverseLimit:   universeLimit,
		FallbackSymbols: splitCSV(env("TYCHE_UNIVERSE_FALLBACK", defaultSymbols)),

		CoreAddr:      env("TYCHE_CORE_ADDR", "localhost:50051"),
		RedisAddr:     env("TYCHE_REDIS_ADDR", "localhost:6379"),
		RedisPassword: env("TYCHE_REDIS_PASSWORD", ""),
		RedisDB:       envInt("TYCHE_REDIS_DB", 0),
		SignalQueue:   env("TYCHE_SIGNAL_QUEUE", "signal"),

		BootCandleCount: envInt("TYCHE_BOOT_CANDLES", envInt("TYCHE_BOOT_CANDLE_COUNT", 300)),
		BootWorkers:     bootWorkers,
		RollInterval:    envDuration("TYCHE_ROLL_INTERVAL_MS", 5*time.Second),
		DecisionEvery:   envDuration("TYCHE_DECISION_INTERVAL_MS", 5*time.Second),
		MonitorEvery:    envDuration("TYCHE_MONITOR_INTERVAL_MS", 5*time.Second),

		YahooBaseURL: strings.TrimRight(env("TYCHE_YAHOO_BASE_URL", "https://query1.finance.yahoo.com"), "/"),
		YahooSuffix:  env("TYCHE_YAHOO_SUFFIX", ".NS"),
		HTTPTimeout:  envDuration("TYCHE_HTTP_TIMEOUT_MS", 15*time.Second),
		HTTPRetries:  envInt("TYCHE_HTTP_RETRIES", 3),

		KiteBaseURL:     strings.TrimRight(env("TYCHE_KITE_BASE_URL", "https://api.kite.trade"), "/"),
		KiteAPIKey:      env("TYCHE_KITE_API_KEY", env("KITE_API_KEY", "")),
		KiteAPISecret:   env("TYCHE_KITE_API_SECRET", env("KITE_API_SECRET", "")),
		KiteAccessToken: env("TYCHE_KITE_ACCESS_TOKEN", env("KITE_ACCESS_TOKEN", "")),
		KiteExchange:    strings.ToUpper(env("TYCHE_KITE_DEFAULT_EXCHANGE", "NSE")),

		InitialCash: envFloat("TYCHE_INITIAL_CASH", 100_000),
		PaperFeeBPS: envFloat("TYCHE_PAPER_FEE_BPS", 0),
		StatusAddr:  env("TYCHE_STATUS_ADDR", ":8080"),
		Decision: DecisionSettings{
			MinConfidence:       envFloat("TYCHE_MIN_CONFIDENCE", 0.70),
			MaxPositions:        envInt("TYCHE_MAX_POSITIONS", 5),
			MaxPositionFraction: envFloat("TYCHE_MAX_POSITION_FRACTION", 0.15),
			StopLossPercent:     envFloat("TYCHE_STOP_LOSS_PCT", 0.05),
			TakeProfitPercent:   envFloat("TYCHE_TAKE_PROFIT_PCT", 0.10),
			HoldDuration:        envDuration("TYCHE_HOLD_MS", 24*time.Hour),
			RebuyCooldown:       envDuration("TYCHE_REBUY_COOLDOWN_MS", 5*time.Minute),
		},
		ShutdownWait: envDuration("TYCHE_SHUTDOWN_GRACE_MS", 5*time.Second),
	}
}

// Env helpers are exported so packages that need a one-off setting (mostly for
// tests) do not have to duplicate parsing logic.

func Env(key, fallback string) string { return env(key, fallback) }

func EnvInt(key string, fallback int) int { return envInt(key, fallback) }

func EnvFloat(key string, fallback float64) float64 { return envFloat(key, fallback) }

func EnvDuration(key string, fallback time.Duration) time.Duration {
	return envDuration(key, fallback)
}

func env(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok && strings.TrimSpace(value) != "" {
		return strings.TrimSpace(value)
	}
	return fallback
}

func envInt(key string, fallback int) int {
	raw := env(key, "")
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return value
}

func envFloat(key string, fallback float64) float64 {
	raw := env(key, "")
	if raw == "" {
		return fallback
	}
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return fallback
	}
	return value
}

// envDuration interprets plain numbers as milliseconds and Go duration strings
// ("5s", "1m") as durations.
func envDuration(key string, fallback time.Duration) time.Duration {
	raw := env(key, "")
	if raw == "" {
		return fallback
	}
	if value, err := strconv.ParseInt(raw, 10, 64); err == nil {
		if value <= 0 {
			return fallback
		}
		return time.Duration(value) * time.Millisecond
	}
	value, err := time.ParseDuration(raw)
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}

func splitCSV(value string) []string {
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	seen := make(map[string]struct{}, len(parts))
	for _, part := range parts {
		symbol := strings.TrimSpace(part)
		if symbol == "" {
			continue
		}
		key := strings.ToUpper(symbol)
		if _, duplicate := seen[key]; duplicate {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, symbol)
	}
	return out
}
