package transaction

import (
	"gateway/app/util/entity"
	"gateway/app/util/transaction/paper"
)

type TransactionHandler interface {
	GetBudget() (float64, error)
	Process(inst entity.TransactionInstruction) error
	ModifyBudget(inst entity.BudgetModification) (float64, error)
}

var _ TransactionHandler = &paper.PaperTrade{}

// NewTransactionHandler creates the paper ledger.  Fee is expressed in basis
// points (1 bps = 0.01%) to model brokerage/slippage conservatively.
func NewTransactionHandler(initialCash, feeBPS float64) (TransactionHandler, error) {
	return paper.NewPaperTradeWithFees(initialCash, feeBPS), nil
}
