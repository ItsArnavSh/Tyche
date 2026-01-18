package producer

import "sync"

type TradeInterface struct {
	lock sync.Mutex
}
