package entity

type SendBoot struct {
	Stocks []TickerHistory
}

type SendRoll struct {
	Stocks []MonoCandle
}

// Kill message to remove that specific stock from scheduler
// For errors or when tickers naturally are not being served
type SendKill struct {
	Stocks []string
}
