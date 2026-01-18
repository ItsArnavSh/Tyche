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

func NewTransactionHandler() (TransactionHandler, error) {
	return paper.NewPaperTrade(10000), nil
}
