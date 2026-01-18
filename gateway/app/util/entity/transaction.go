package entity

import "time"

type Action int

const (
	BUY Action = iota
	SELL
	HOLD
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

func (a Action) String() string {
	switch a {
	case BUY:
		return "BUY"
	case SELL:
		return "SELL"
	case HOLD:
		return "HOLD"
	default:
		return "UNKNOWN"
	}
}

type TransactionInstruction struct {
	Ticker     string
	Action     Action
	Quantity   uint32
	Price      float64
	Confidence float64
	Reason     string
	MaxVal     float64
	SellAfter  time.Time
	MinValSell float64
}
type BudgetModification struct {
	Amount   float64
	Action   Action // ADD REMOVE
	Platform platform
}
