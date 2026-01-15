package entity

type action int

const (
	BUY action = iota
	SELL
)

type TransactionInstruction struct {
	Ticker   string
	Action   action
	Quantity uint32
}
