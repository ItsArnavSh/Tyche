package entity

type action int

const (
	BUY action = iota
	SELL
	ADD
	REMOVE
)

type platform int

const (
	ZERODHA platform = iota
	GROWW
	COINDCX
)

type currency int

type TransactionInstruction struct {
	Ticker   string
	Action   action //BUY SELL
	Quantity uint32
	Price    float64
}

type BudgetModification struct {
	Amount   float64
	Action   action // ADD REMOVE
	Platform platform
}
