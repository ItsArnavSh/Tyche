package server

import (
	"context"
	"sort"
	"time"

	"gateway/app/status"
	"gateway/app/util/bucket"
	"gateway/app/util/transaction/paper"
)

// StatusSnapshot assembles the read-only view served to the dashboard. It is
// intentionally a pull-based snapshot: the trading loop is never blocked on an
// HTTP client, and every field is derived from state the gateway already owns.
func (s *Server) StatusSnapshot() status.Snapshot {
	now := time.Now()
	activity := s.status.Activity()
	ledger := s.ledger.Snapshot()
	signalStates := s.bucket.Snapshot()
	monitorPositions := s.monitor.ActivePositions()
	monitorStats := s.monitor.Stats()

	signalsByTicker := make(map[string]statusSignalState, len(signalStates))
	for _, state := range signalStates {
		signalsByTicker[state.Name] = statusSignalState{
			aggregate:   state.AggregateConfidence,
			peak:        state.PeakConfidence,
			signalCount: len(state.Signals),
		}
	}

	monitorByTicker := make(map[string]statusPositionMeta, len(monitorPositions))
	for _, position := range monitorPositions {
		monitorByTicker[position.Ticker] = statusPositionMeta{
			stopLoss:   position.MinValSell,
			takeProfit: position.MaxVal,
			confidence: position.Confidence,
			reason:     position.Reason,
			openedAt:   position.OpenedAt,
			sellAfter:  position.SellAfter,
		}
	}

	positions := make([]status.Position, 0, len(ledger.Positions))
	marketValue := 0.0
	unrealised := 0.0
	for _, holding := range ledger.Positions {
		currentPrice := s.producer.GetCurrentValue(holding.Ticker)
		if currentPrice <= 0 {
			currentPrice = holding.AvgPrice
		}
		value := currentPrice * float64(holding.Quantity)
		pnl := (currentPrice - holding.AvgPrice) * float64(holding.Quantity)
		marketValue += value
		unrealised += pnl

		position := status.Position{
			Ticker:        holding.Ticker,
			Quantity:      holding.Quantity,
			EntryPrice:    holding.AvgPrice,
			CurrentPrice:  currentPrice,
			MarketValue:   value,
			UnrealisedPnL: pnl,
		}
		position.UnrealisedPnLPct = percent(pnl, holding.AvgPrice*float64(holding.Quantity))
		if meta, ok := monitorByTicker[holding.Ticker]; ok {
			position.StopLoss = meta.stopLoss
			position.TakeProfit = meta.takeProfit
			position.Confidence = meta.confidence
			position.Reason = meta.reason
			position.OpenedAt = meta.openedAt
			position.SellAfter = meta.sellAfter
		}
		if state, ok := signalsByTicker[holding.Ticker]; ok {
			position.AggregateConfidence = state.aggregate
			position.PeakConfidence = state.peak
			position.SignalCount = state.signalCount
		}
		positions = append(positions, position)
	}

	equity := ledger.Cash + marketValue
	totalPnL := equity - ledger.InitialCash
	portfolio := status.Portfolio{
		InitialCash:   ledger.InitialCash,
		Cash:          ledger.Cash,
		MarketValue:   marketValue,
		Equity:        equity,
		RealisedPnL:   ledger.RealisedPnL,
		UnrealisedPnL: unrealised,
		TotalPnL:      totalPnL,
		TotalPnLPct:   percent(totalPnL, ledger.InitialCash),
		FeesPaid:      ledger.FeesPaid,
		Buys:          ledger.Buys,
		Sells:         ledger.Sells,
		Wins:          ledger.Wins,
		Losses:        ledger.Losses,
	}

	closedTrades := make([]status.ClosedTrade, 0)
	monitorClosed := s.monitor.ClosedTrades()
	for i := len(monitorClosed) - 1; i >= 0; i-- {
		trade := monitorClosed[i]
		duration := int64(0)
		if !trade.OpenedAt.IsZero() {
			duration = int64(trade.ClosedAt.Sub(trade.OpenedAt).Seconds())
		}
		closedTrades = append(closedTrades, status.ClosedTrade{
			Ticker:    trade.Ticker,
			Quantity:  trade.Quantity,
			Entry:     trade.Entry,
			Exit:      trade.Exit,
			PnL:       trade.PnL,
			PnLPct:    percent(trade.PnL, trade.Entry*float64(trade.Quantity)),
			Reason:    trade.Reason,
			OpenedAt:  trade.OpenedAt,
			ClosedAt:  trade.ClosedAt,
			DurationS: duration,
		})
	}

	market := s.marketSnapshot(signalStates, ledger.Positions)

	redisConnected := false
	if s.broker != nil {
		redisCtx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
		redisConnected = s.broker.Ping(redisCtx) == nil
		cancel()
	}
	coreConnected := s.grpc != nil && s.grpc.Connected()
	rollInterval := time.Duration(0)
	if s.producer != nil {
		rollInterval = s.producer.RollInterval()
	}
	feed := status.Feed{
		Source:          s.cfg.DataSource,
		Symbols:         append([]string(nil), s.cfg.Symbols...),
		RollIntervalMS:  rollInterval.Milliseconds(),
		CoreConnected:   coreConnected,
		RedisConnected:  redisConnected,
		LastRollAt:      timePointer(activity.Health.LastRollAt),
		LastBootAt:      timePointer(activity.Health.LastBootAt),
		LastRollUpdates: activity.Health.LastRollUpdates,
		Missing:         activity.Health.Missing,
		MissingTickers:  append([]string{}, activity.Health.MissingTickers...),
		LastRollError:   activity.Health.LastRollError,
		LastError:       activity.Health.LastError,
		LastErrorScope:  activity.Health.LastErrorScope,
	}

	return status.Snapshot{
		GeneratedAt:   now,
		UptimeSeconds: int64(now.Sub(s.status.StartedAt()).Seconds()),
		Mode:          "paper",
		State:         systemState(activity, coreConnected, redisConnected),
		Portfolio:     portfolio,
		Positions:     positions,
		ClosedTrades:  closedTrades,
		Decisions:     activity.Orders,
		Signals:       activity.Signals,
		Market:        market,
		Feed:          feed,
		Stats: status.Stats{
			ActivePositions: len(positions),
			MaxPositions:    s.cfg.Decision.MaxPositions,
			ClosedTrades:    monitorStats.ClosedTrades,
			Wins:            monitorStats.Wins,
			Losses:          monitorStats.Losses,
			SignalsReceived: activity.Counters.SignalsReceived,
			Orders:          activity.Counters.Orders,
			Fills:           activity.Counters.Fills,
			Rejections:      activity.Counters.Rejections,
			BucketTickers:   len(signalStates),
		},
		Risk: status.Risk{
			MinConfidence:       s.cfg.Decision.MinConfidence,
			MaxPositions:        s.cfg.Decision.MaxPositions,
			MaxPositionFraction: s.cfg.Decision.MaxPositionFraction,
			StopLossPercent:     s.cfg.Decision.StopLossPercent,
			TakeProfitPercent:   s.cfg.Decision.TakeProfitPercent,
			HoldDurationMS:      s.cfg.Decision.HoldDuration.Milliseconds(),
			RebuyCooldownMS:     s.cfg.Decision.RebuyCooldown.Milliseconds(),
		},
		Errors: activity.Errors,
	}
}

type statusSignalState struct {
	aggregate   float64
	peak        float64
	signalCount int
}

type statusPositionMeta struct {
	stopLoss   float64
	takeProfit float64
	confidence float64
	reason     string
	openedAt   time.Time
	sellAfter  time.Time
}

func (s *Server) marketSnapshot(
	signalStates []bucket.SignalState,
	holdings []paper.Position,
) []status.MarketSymbol {
	stateByTicker := make(map[string]bucket.SignalState, len(signalStates))
	for _, state := range signalStates {
		stateByTicker[state.Name] = state
	}
	positionTickers := make(map[string]struct{}, len(holdings))
	for _, holding := range holdings {
		positionTickers[holding.Ticker] = struct{}{}
	}

	order := make([]string, 0, len(s.cfg.Symbols)+len(signalStates))
	seen := make(map[string]struct{}, len(s.cfg.Symbols)+len(signalStates))
	add := func(ticker string) {
		if ticker == "" {
			return
		}
		if _, ok := seen[ticker]; ok {
			return
		}
		seen[ticker] = struct{}{}
		order = append(order, ticker)
	}
	for _, ticker := range s.cfg.Symbols {
		add(ticker)
	}
	extras := make(map[string]struct{})
	for _, state := range signalStates {
		if _, ok := seen[state.Name]; !ok {
			extras[state.Name] = struct{}{}
		}
	}
	for _, holding := range holdings {
		if _, ok := seen[holding.Ticker]; !ok {
			extras[holding.Ticker] = struct{}{}
		}
	}
	for _, ticker := range sortedTickers(extras) {
		add(ticker)
	}

	out := make([]status.MarketSymbol, 0, len(order))
	for _, ticker := range order {
		state := stateByTicker[ticker]
		_, hasPosition := positionTickers[ticker]
		out = append(out, status.MarketSymbol{
			Ticker:              ticker,
			Price:               s.producer.GetCurrentValue(ticker),
			AggregateConfidence: state.AggregateConfidence,
			PeakConfidence:      state.PeakConfidence,
			SignalCount:         len(state.Signals),
			HasPosition:         hasPosition,
		})
	}
	return out
}

func systemState(activity status.Activity, coreConnected, redisConnected bool) string {
	if !coreConnected || !redisConnected || activity.Health.LastRollError != "" {
		return "degraded"
	}
	if activity.Health.LastBootAt.IsZero() || activity.Health.LastRollAt.IsZero() {
		return "starting"
	}
	return "running"
}

func percent(value, base float64) float64 {
	if base <= 0 {
		return 0
	}
	return value / base * 100
}

func timePointer(value time.Time) *time.Time {
	if value.IsZero() {
		return nil
	}
	return &value
}

func sortedTickers(values map[string]struct{}) []string {
	out := make([]string, 0, len(values))
	for value := range values {
		out = append(out, value)
	}
	sort.Strings(out)
	return out
}
