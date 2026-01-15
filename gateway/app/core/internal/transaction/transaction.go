package transaction

import "gateway/app/util/entity"

type Transaction interface {
	GetBudget() (float64, error)
	Process(inst entity.TransactionInstruction) error
}
