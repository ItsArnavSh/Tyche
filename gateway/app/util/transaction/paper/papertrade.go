package paper

import (
	"fmt"
	"gateway/app/util/entity"
)

type PaperTrade struct {
	budget float64
}

func (p *PaperTrade) GetBudget() (float64, error) {
	return p.budget, nil
}

func (p *PaperTrade) Process(inst entity.TransactionInstruction) error {
	switch inst.Action {
	case entity.BUY:
		cost := float64(inst.Quantity) * (inst.Price)
		if cost > p.budget {
			return fmt.Errorf("Not enough budget")
		}
		p.budget -= cost
	case entity.SELL:
		p.budget += float64(inst.Quantity) * inst.Price
	}
	return nil
}

func (p *PaperTrade) ModifyBudget(inst entity.BudgetModification) (float64, error) {
	switch inst.Action {
	case entity.ADD:
		p.budget += inst.Amount
	case entity.REMOVE:
		p.budget -= inst.Amount
		if p.budget < 0 {
			p.budget = 0
		}
	}
	return p.budget, nil
}
func NewPaperTrade(budget float64) *PaperTrade {
	return &PaperTrade{budget: budget}
}
