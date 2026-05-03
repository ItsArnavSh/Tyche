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
	log.Printf("[DecisionLayer] Initializing — bucket=%+v monitor=%+v", bucket, monitor)
	return DecisionLayer{
		transaction:    th,
		bucket:         bucket,
		signal_channel: sig,
		monitor:        monitor,
	}
}

func (d *DecisionLayer) StartSignalPoller() {
	log.Println("[SignalPoller] Started — waiting for signals")
	for signal := range d.signal_channel {
		log.Printf("[SignalPoller] Received signal: %+v", signal)
		d.bucket.SignalBucket(signal)
	}
	log.Println("[SignalPoller] Signal channel closed — exiting")
}

func (d *DecisionLayer) StartDecisionMaking(ctx context.Context, interval int) {
	log.Println("[DecisionMaking] Started")
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			budget, err := d.transaction.GetBudget()
			if err != nil {
				log.Printf("[DecisionMaking] Failed to get budget: %v", err)
				continue
			}
			log.Printf("[DecisionMaking] Tick — budget=%.2f", budget)

			if d.bucket.Producer == nil {
				log.Println("[DecisionMaking] bucket.Producer is nil — skipping tick")
				continue
			}

			top_choices := d.bucket.GetTopNinBudget(5, budget)
			log.Printf("[DecisionMaking] Top choices: %+v", top_choices)

			if len(top_choices) == 0 {
				log.Println("[DecisionMaking] No choices returned — DecayMap may be empty or nothing fits budget")
				continue
			}

			instructions := d.considerChoices(top_choices)
			log.Printf("[DecisionMaking] Instructions generated: %d", len(instructions))

			if len(instructions) > 0 {
				d.BeginTransaction(instructions)
			}

		case <-ctx.Done():
			log.Println("[DecisionMaking] Context cancelled — stopping")
			return
		}
	}
}

func (d *DecisionLayer) considerChoices(choices []entity.SignalConf) []entity.TransactionInstruction {
	log.Printf("[considerChoices] Evaluating %d choices", len(choices))

	budget, err := d.transaction.GetBudget()
	if err != nil {
		log.Printf("[considerChoices] Failed to get budget: %v", err)
		return nil
	}
	log.Printf("[considerChoices] Budget: %.2f", budget)

	const (
		minConfidence     = 0.70
		maxPositions      = 5
		budgetPerTrade    = 0.15
		stopLossPercent   = 0.05
		takeProfitPercent = 0.10
		holdDuration      = 24 * time.Hour
	)

	var qualified []entity.SignalConf
	for _, choice := range choices {
		if choice.ResultantConf >= minConfidence {
			qualified = append(qualified, choice)
		} else {
			log.Printf("[considerChoices] Skipping %s — confidence %.2f below threshold %.2f", choice.Name, choice.ResultantConf, minConfidence)
		}
	}

	if len(qualified) == 0 {
		log.Println("[considerChoices] No qualified signals after confidence filter")
		return nil
	}
	log.Printf("[considerChoices] Qualified signals: %d", len(qualified))

	sort.Slice(qualified, func(i, j int) bool {
		return qualified[i].ResultantConf > qualified[j].ResultantConf
	})

	limit := min(len(qualified), maxPositions)
	positionBudget := budget * budgetPerTrade
	log.Printf("[considerChoices] Position budget per trade: %.2f", positionBudget)

	var instructions []entity.TransactionInstruction
	for i := 0; i < limit; i++ {
		signal := qualified[i]
		log.Printf("[considerChoices] Processing signal: %+v", signal)

		price := d.bucket.Producer.GetCurrentValue(signal.Name)
		log.Printf("[considerChoices] Current price for %s: %.4f", signal.Name, price)

		if price <= 0 {
			log.Printf("[considerChoices] Skipping %s — invalid price %.4f", signal.Name, price)
			continue
		}

		quantity := uint32(positionBudget / price)
		if quantity == 0 {
			log.Printf("[considerChoices] Skipping %s — quantity rounds to 0 (budget=%.2f price=%.4f)", signal.Name, positionBudget, price)
			continue
		}

		maxVal := price * (1 + takeProfitPercent)
		minValSell := price * (1 - stopLossPercent)
		sellAfter := time.Now().Add(holdDuration)

		log.Printf("[considerChoices] Instruction — ticker=%s qty=%d price=%.4f takeProfit=%.4f stopLoss=%.4f", signal.Name, quantity, price, maxVal, minValSell)

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

	log.Printf("[considerChoices] Total instructions: %d", len(instructions))
	return instructions
}

func (d *DecisionLayer) BeginTransaction(insts []entity.TransactionInstruction) {
	log.Printf("[BeginTransaction] Processing %d instructions", len(insts))
	for _, inst := range insts {
		log.Printf("[BeginTransaction] Executing: %+v", inst)
		d.transaction.Process(inst)
		d.monitor.AddPosition(inst)
		log.Printf("[BeginTransaction] Done: %s", inst.Ticker)
	}
}
