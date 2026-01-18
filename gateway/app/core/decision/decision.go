package decision

import (
	"context"
	"fmt"
	"gateway/app/core/monitor"
	"gateway/app/util/bucket"
	"gateway/app/util/entity"
	"gateway/app/util/transaction"
	"log"
	"sort"
	"time"
)

type DecisionLayer struct {
	transaction    transaction.TransactionHandler
	bucket         bucket.Bucket
	signal_channel <-chan entity.Signal
	monitor        *monitor.TradeMonitor
}

func NewDecisionLayer(th transaction.TransactionHandler, bucket bucket.Bucket, sig <-chan entity.Signal, monitor *monitor.TradeMonitor) DecisionLayer {
	return DecisionLayer{
		transaction:    th,
		bucket:         bucket,
		signal_channel: sig,
		monitor:        monitor,
	}
}

func (d *DecisionLayer) StartSignalPoller() {
	for signal := range d.signal_channel {
		d.bucket.SignalBucket(signal)
	}
}
func (d *DecisionLayer) StartDecisionMaking(ctx context.Context, interval int) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			{
				budget, _ := d.transaction.GetBudget()
				top_choices := d.bucket.GetTopNinBudget(5, budget)
				d.considerChoices(top_choices)
			}
		case <-ctx.Done():
			log.Println("Stopping ticker")
			return
		}
	}
}
func (d *DecisionLayer) considerChoices(choices []entity.SignalConf) []entity.TransactionInstruction {
	budget, _ := d.transaction.GetBudget()

	const (
		minConfidence     = 0.70           // Only buy if confidence >= 70%
		maxPositions      = 5              // Max number of buys at once
		budgetPerTrade    = 0.15           // Use 15% of budget per trade
		stopLossPercent   = 0.05           // Sell if drops 5%
		takeProfitPercent = 0.10           // Sell if gains 10%
		holdDuration      = 24 * time.Hour // Hold for 24 hours max
	)

	// Filter qualified signals
	var qualified []entity.SignalConf
	for _, choice := range choices {
		if choice.ResultantConf >= minConfidence {
			qualified = append(qualified, choice)
		}
	}

	if len(qualified) == 0 {
		return nil
	}

	// Sort by confidence (highest first)
	sort.Slice(qualified, func(i, j int) bool {
		return qualified[i].ResultantConf > qualified[j].ResultantConf
	})

	// Take top N
	limit := min(len(qualified), maxPositions)

	var instructions []entity.TransactionInstruction
	positionBudget := budget * budgetPerTrade

	for i := 0; i < limit; i++ {
		signal := qualified[i]

		// Get current price
		price := d.bucket.Producer.GetCurrentValue(signal.Name)

		// Calculate quantity
		quantity := uint32(positionBudget / price)
		if quantity == 0 {
			continue
		}

		// Calculate sell targets
		maxVal := price * (1 + takeProfitPercent)
		minValSell := price * (1 - stopLossPercent)
		sellAfter := time.Now().Add(holdDuration)

		instructions = append(instructions, entity.TransactionInstruction{
			Ticker:     signal.Name,
			Action:     entity.BUY,
			Quantity:   quantity,
			Price:      price,
			Confidence: signal.ResultantConf,
			Reason:     fmt.Sprintf("High confidence: %.1f%%", signal.ResultantConf*100),
			MaxVal:     maxVal,
			SellAfter:  sellAfter,
			MinValSell: minValSell,
		})
	}

	return instructions
}
func (d *DecisionLayer) BeginTransaction(insts []entity.TransactionInstruction) {
	for _, inst := range insts {
		d.transaction.Process(inst)
		d.monitor.AddPosition(inst)
	}
}
