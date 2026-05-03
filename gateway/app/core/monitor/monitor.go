package monitor

import (
	"context"
	"gateway/app/util/bucket"
	"gateway/app/util/entity"
	"gateway/app/util/transaction"
	"log"
	"time"
)

type TradeMonitor struct {
	TransactionHandler transaction.TransactionHandler
	ActivePositions    []entity.TransactionInstruction
	ConfidenceBuckets  bucket.Bucket
}

func NewTradeMonitor(th transaction.TransactionHandler, cb bucket.Bucket) *TradeMonitor {
	log.Printf("[TradeMonitor] Initializing — producer=%+v", cb.Producer)
	return &TradeMonitor{
		TransactionHandler: th,
		ActivePositions:    make([]entity.TransactionInstruction, 0),
		ConfidenceBuckets:  cb,
	}
}

func (m *TradeMonitor) AddPosition(position entity.TransactionInstruction) {
	log.Printf("[TradeMonitor] Adding position: %s qty=%d price=%.4f takeProfit=%.4f stopLoss=%.4f sellAfter=%s",
		position.Ticker, position.Quantity, position.Price, position.MaxVal, position.MinValSell, position.SellAfter.Format(time.RFC3339))
	m.ActivePositions = append(m.ActivePositions, position)
}

func (m *TradeMonitor) StartMonitor(ctx context.Context) {
	log.Println("[TradeMonitor] Started")
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			log.Printf("[TradeMonitor] Tick — active positions: %d", len(m.ActivePositions))
			m.checkPositions()
		case <-ctx.Done():
			log.Println("[TradeMonitor] Context cancelled — stopping")
			return
		}
	}
}

func (m *TradeMonitor) checkPositions() {
	if len(m.ActivePositions) == 0 {
		log.Println("[TradeMonitor] No active positions to check")
		return
	}

	var remainingPositions []entity.TransactionInstruction
	for _, position := range m.ActivePositions {
		log.Printf("[TradeMonitor] Checking position: %s", position.Ticker)
		shouldSell, reason := m.shouldSellPosition(position)
		if shouldSell {
			log.Printf("[TradeMonitor] Selling %s — reason: %s", position.Ticker, reason)
			m.sellPosition(position, reason)
		} else {
			log.Printf("[TradeMonitor] Holding %s", position.Ticker)
			remainingPositions = append(remainingPositions, position)
		}
	}
	m.ActivePositions = remainingPositions
	log.Printf("[TradeMonitor] Positions remaining after check: %d", len(m.ActivePositions))
}

func (m *TradeMonitor) shouldSellPosition(position entity.TransactionInstruction) (bool, string) {
	if m.ConfidenceBuckets.Producer == nil {
		log.Printf("[shouldSell] Producer is nil — cannot evaluate %s", position.Ticker)
		return false, ""
	}

	currentPrice := m.ConfidenceBuckets.Producer.GetCurrentValue(position.Ticker)
	log.Printf("[shouldSell] %s — current=%.4f target=%.4f stop=%.4f sellAfter=%s",
		position.Ticker, currentPrice, position.MaxVal, position.MinValSell, position.SellAfter.Format(time.RFC3339))

	if currentPrice >= position.MaxVal {
		log.Printf("[shouldSell] %s — take profit hit (%.4f >= %.4f)", position.Ticker, currentPrice, position.MaxVal)
		return true, "Take profit target reached"
	}

	if currentPrice <= position.MinValSell {
		log.Printf("[shouldSell] %s — stop loss hit (%.4f <= %.4f)", position.Ticker, currentPrice, position.MinValSell)
		return true, "Stop loss triggered"
	}

	if time.Now().After(position.SellAfter) {
		log.Printf("[shouldSell] %s — time limit exceeded (sellAfter=%s)", position.Ticker, position.SellAfter.Format(time.RFC3339))
		return true, "Time limit exceeded"
	}

	currentConf, err := m.ConfidenceBuckets.GetConfidence(position.Ticker)
	if err != nil {
		log.Printf("[shouldSell] %s — could not get confidence: %v", position.Ticker, err)
	} else {
		confidenceDrop := position.Confidence - currentConf
		log.Printf("[shouldSell] %s — confidence at buy=%.2f current=%.2f drop=%.2f", position.Ticker, position.Confidence, currentConf, confidenceDrop)
		if confidenceDrop > 0.20 {
			log.Printf("[shouldSell] %s — confidence dropped too much (%.2f > 0.20)", position.Ticker, confidenceDrop)
			return true, "Confidence dropped significantly"
		}
	}

	return false, ""
}

func (m *TradeMonitor) sellPosition(position entity.TransactionInstruction, reason string) {
	if m.ConfidenceBuckets.Producer == nil {
		log.Printf("[sellPosition] Producer is nil — cannot sell %s", position.Ticker)
		return
	}

	currentPrice := m.ConfidenceBuckets.Producer.GetCurrentValue(position.Ticker)
	log.Printf("[sellPosition] %s — selling %d shares at %.4f (reason: %s)", position.Ticker, position.Quantity, currentPrice, reason)

	sellInstruction := entity.TransactionInstruction{
		Ticker:   position.Ticker,
		Action:   entity.SELL,
		Quantity: position.Quantity,
		Price:    currentPrice,
		Reason:   reason,
	}

	err := m.TransactionHandler.Process(sellInstruction)
	if err != nil {
		log.Printf("[sellPosition] Failed to process sell for %s: %v", position.Ticker, err)
		return
	}

	buyValue := float64(position.Quantity) * position.Price
	sellValue := float64(position.Quantity) * currentPrice
	profitLoss := sellValue - buyValue
	profitLossPct := (profitLoss / buyValue) * 100

	log.Printf("[sellPosition] SOLD %s — qty=%d buyPrice=%.4f sellPrice=%.4f P/L=%.2f (%.2f%%) reason=%s",
		position.Ticker, position.Quantity, position.Price, currentPrice, profitLoss, profitLossPct, reason)
}

func (m *TradeMonitor) GetActivePositionCount() int {
	count := len(m.ActivePositions)
	log.Printf("[TradeMonitor] Active position count: %d", count)
	return count
}

func (m *TradeMonitor) PrintSummary() {
	log.Printf("[TradeMonitor] Summary — %d active positions:", len(m.ActivePositions))
	if m.ConfidenceBuckets.Producer == nil {
		log.Println("[TradeMonitor] WARNING: Producer is nil — cannot fetch current prices")
		return
	}
	for _, pos := range m.ActivePositions {
		currentPrice := m.ConfidenceBuckets.Producer.GetCurrentValue(pos.Ticker)
		unrealised := (currentPrice - pos.Price) * float64(pos.Quantity)
		log.Printf("[TradeMonitor]   %s qty=%d buyPrice=%.4f current=%.4f target=%.4f stop=%.4f unrealised=%.2f",
			pos.Ticker, pos.Quantity, pos.Price, currentPrice, pos.MaxVal, pos.MinValSell, unrealised)
	}
}
