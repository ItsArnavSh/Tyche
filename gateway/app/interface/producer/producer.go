package producer

import (
	"gateway/app/interface/producer/fakeval"
	"gateway/app/util/entity"
)

type Producer interface {
	BootRequest(req []entity.MonoCandle) []entity.TickerHistory
	RollRequest(req []entity.MonoCandle) []entity.LatestVal
	GetCurrentValue(name string) float64
}

var _ Producer = &fakeval.FakeValGen{}

func NewProducer() (Producer, error) {
	return fakeval.NewFakeValGen(), nil
}
