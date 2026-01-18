package monitor

import "gateway/app/util/transaction"

type TradeMonitor struct {
	TransactionHandler transaction.TransactionHandler
}

func NewTradeMonitor(th transaction.TransactionHandler) TradeMonitor {
	return TradeMonitor{TransactionHandler: th}
}

func (m *TradeMonitor) StartMonitor() {

}
