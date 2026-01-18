package monitor

import (
	"context"
	"log"
	"time"

	"gateway/app/util/bucket"
	"gateway/app/util/entity"
	"gateway/app/util/transaction"
)

type TradeMonitor struct {
	TransactionHandler transaction.TransactionHandler
	ActivePositions    []entity.TransactionInstruction
	ConfidenceBuckets  bucket.Bucket
}

func NewTradeMonitor(th transaction.TransactionHandler, cb bucket.Bucket) *TradeMonitor {
	return &TradeMonitor{
		TransactionHandler: th,
		ActivePositions:    make([]entity.TransactionInstruction, 0),
		ConfidenceBuckets:  cb,
	}
}

// AddPosition adds a new position to monitor
func (m *TradeMonitor) AddPosition(position entity.TransactionInstruction) {
	m.ActivePositions = append(m.ActivePositions, position)
	log.Printf("Added position to monitor: %s (%d shares)", position.Ticker, position.Quantity)
}

// StartMonitor loops infinitely checking positions every 5 seconds
func (m *TradeMonitor) StartMonitor(ctx context.Context) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	log.Println("Trade monitor started")

	for {
		select {
		case <-ticker.C:
			m.checkPositions()
		case <-ctx.Done():
			log.Println("Trade monitor stopped")
			return
		}
	}
}

func (m *TradeMonitor) checkPositions() {
	if len(m.ActivePositions) == 0 {
		return
	}

	var remainingPositions []entity.TransactionInstruction

	for _, position := range m.ActivePositions {
		shouldSell, reason := m.shouldSellPosition(position)

		if shouldSell {
			m.sellPosition(position, reason)
		} else {
			remainingPositions = append(remainingPositions, position)
		}
	}

	m.ActivePositions = remainingPositions
}

func (m *TradeMonitor) shouldSellPosition(position entity.TransactionInstruction) (bool, string) {
	// Get current price
	currentPrice := m.ConfidenceBuckets.Producer.GetCurrentValue(position.Ticker)

	// Check if hit max profit target
	if currentPrice >= position.MaxVal {
		return true, "Take profit target reached"
	}

	// Check if hit stop loss
	if currentPrice <= position.MinValSell {
		return true, "Stop loss triggered"
	}

	// Check if time expired
	if time.Now().After(position.SellAfter) {
		return true, "Time limit exceeded"
	}

	// Check if confidence dropped
	currentConf, err := m.ConfidenceBuckets.GetConfidence(position.Ticker)
	if err == nil {
		// If confidence dropped significantly (e.g., 20% drop)
		confidenceDrop := position.Confidence - currentConf
		if confidenceDrop > 0.20 {
			return true, "Confidence dropped significantly"
		}
	}

	return false, ""
}

func (m *TradeMonitor) sellPosition(position entity.TransactionInstruction, reason string) {
	currentPrice := m.ConfidenceBuckets.Producer.GetCurrentValue(position.Ticker)

	sellInstruction := entity.TransactionInstruction{
		Ticker:   position.Ticker,
		Action:   entity.SELL,
		Quantity: position.Quantity,
		Price:    currentPrice,
		Reason:   reason,
	}

	err := m.TransactionHandler.Process(sellInstruction)
	if err != nil {
		log.Printf("Error selling %s: %v", position.Ticker, err)
		return
	}

	// Calculate profit/loss
	buyValue := float64(position.Quantity) * position.Price
	sellValue := float64(position.Quantity) * currentPrice
	profitLoss := sellValue - buyValue
	profitLossPct := (profitLoss / buyValue) * 100

	log.Printf("SOLD %s: %d shares @ $%.2f | P/L: $%.2f (%.2f%%) | Reason: %s",
		position.Ticker,
		position.Quantity,
		currentPrice,
		profitLoss,
		profitLossPct,
		reason,
	)
}

// GetActivePositionCount returns number of active positions
func (m *TradeMonitor) GetActivePositionCount() int {
	return len(m.ActivePositions)
}

// PrintSummary prints current positions being monitored
func (m *TradeMonitor) PrintSummary() {
	log.Printf("Monitoring %d active positions:", len(m.ActivePositions))
	for _, pos := range m.ActivePositions {
		currentPrice := m.ConfidenceBuckets.Producer.GetCurrentValue(pos.Ticker)
		log.Printf("  %s: %d shares @ $%.2f (current: $%.2f, target: $%.2f, stop: $%.2f)",
			pos.Ticker,
			pos.Quantity,
			pos.Price,
			currentPrice,
			pos.MaxVal,
			pos.MinValSell,
		)
	}
}
