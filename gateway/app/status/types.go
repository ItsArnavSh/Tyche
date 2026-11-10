package status

import "time"

// Snapshot is the complete JSON document served to the dashboard.
type Snapshot struct {
	GeneratedAt   time.Time      `json:"generated_at"`
	UptimeSeconds int64          `json:"uptime_seconds"`
	Mode          string         `json:"mode"`
	State         string         `json:"state"`
	Portfolio     Portfolio      `json:"portfolio"`
	Positions     []Position     `json:"positions"`
	ClosedTrades  []ClosedTrade  `json:"closed_trades"`
	Decisions     []OrderEvent   `json:"decisions"`
	Signals       []SignalEvent  `json:"signals"`
	Market        []MarketSymbol `json:"market"`
	Feed          Feed           `json:"feed"`
	Stats         Stats          `json:"stats"`
	Risk          Risk           `json:"risk"`
	Errors        []ErrorEvent   `json:"errors,omitempty"`
}

// Portfolio is the current paper-account state. Equity is cash plus the
// market value of open positions; PnL includes fees already paid.
type Portfolio struct {
	InitialCash   float64 `json:"initial_cash"`
	Cash          float64 `json:"cash"`
	MarketValue   float64 `json:"market_value"`
	Equity        float64 `json:"equity"`
	RealisedPnL   float64 `json:"realised_pnl"`
	UnrealisedPnL float64 `json:"unrealised_pnl"`
	TotalPnL      float64 `json:"total_pnl"`
	TotalPnLPct   float64 `json:"total_pnl_pct"`
	FeesPaid      float64 `json:"fees_paid"`
	Buys          int     `json:"buys"`
	Sells         int     `json:"sells"`
	Wins          int     `json:"wins"`
	Losses        int     `json:"losses"`
}

// Position is an open paper position enriched with the live quote and the
// current signal state for that ticker.
type Position struct {
	Ticker              string    `json:"ticker"`
	Quantity            uint32    `json:"quantity"`
	EntryPrice          float64   `json:"entry_price"`
	CurrentPrice        float64   `json:"current_price"`
	MarketValue         float64   `json:"market_value"`
	UnrealisedPnL       float64   `json:"unrealised_pnl"`
	UnrealisedPnLPct    float64   `json:"unrealised_pnl_pct"`
	StopLoss            float64   `json:"stop_loss"`
	TakeProfit          float64   `json:"take_profit"`
	Confidence          float64   `json:"confidence"`
	Reason              string    `json:"reason"`
	OpenedAt            time.Time `json:"opened_at"`
	SellAfter           time.Time `json:"sell_after"`
	AggregateConfidence float64   `json:"aggregate_confidence"`
	PeakConfidence      float64   `json:"peak_confidence"`
	SignalCount         int       `json:"signal_count"`
}

// MarketSymbol is the dashboard's live watchlist row.
type MarketSymbol struct {
	Ticker              string  `json:"ticker"`
	Price               float64 `json:"price"`
	AggregateConfidence float64 `json:"aggregate_confidence"`
	PeakConfidence      float64 `json:"peak_confidence"`
	SignalCount         int     `json:"signal_count"`
	HasPosition         bool    `json:"has_position"`
}

// Feed reports whether the Go-owned data path is healthy.
type Feed struct {
	Source          string     `json:"source"`
	Symbols         []string   `json:"symbols"`
	RollIntervalMS  int64      `json:"roll_interval_ms"`
	CoreConnected   bool       `json:"core_connected"`
	RedisConnected  bool       `json:"redis_connected"`
	LastRollAt      *time.Time `json:"last_roll_at,omitempty"`
	LastBootAt      *time.Time `json:"last_boot_at,omitempty"`
	LastRollUpdates int        `json:"last_roll_updates"`
	Missing         int        `json:"missing"`
	MissingTickers  []string   `json:"missing_tickers"`
	LastRollError   string     `json:"last_roll_error,omitempty"`
	LastError       string     `json:"last_error,omitempty"`
	LastErrorScope  string     `json:"last_error_scope,omitempty"`
}

// Stats aggregates event counters and monitor state.
type Stats struct {
	ActivePositions int   `json:"active_positions"`
	MaxPositions    int   `json:"max_positions"`
	ClosedTrades    int   `json:"closed_trades"`
	Wins            int   `json:"wins"`
	Losses          int   `json:"losses"`
	SignalsReceived int64 `json:"signals_received"`
	Orders          int64 `json:"orders"`
	Fills           int64 `json:"fills"`
	Rejections      int64 `json:"rejections"`
	BucketTickers   int   `json:"bucket_tickers"`
}

// Risk publishes the active sizing and exit controls for transparency.
type Risk struct {
	MinConfidence       float64 `json:"min_confidence"`
	MaxPositions        int     `json:"max_positions"`
	MaxPositionFraction float64 `json:"max_position_fraction"`
	StopLossPercent     float64 `json:"stop_loss_percent"`
	TakeProfitPercent   float64 `json:"take_profit_percent"`
	HoldDurationMS      int64   `json:"hold_duration_ms"`
	RebuyCooldownMS     int64   `json:"rebuy_cooldown_ms"`
}
