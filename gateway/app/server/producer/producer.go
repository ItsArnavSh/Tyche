package producer

import (
	"gateway/app/server/producer/fakeval"
	"gateway/app/util/entity"
)

type Producer interface {
	BootRequest(req []entity.MonoCandle) []entity.TickerHistory
	RollRequest(req []entity.MonoCandle) []entity.LatestVal
}

var _ Producer = &fakeval.FakeValGen{}
