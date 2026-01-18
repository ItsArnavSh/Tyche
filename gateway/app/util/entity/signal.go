package entity

import (
	"time"
)

type Signal struct {
	Name       string     `json:"name"`
	Confidence float64    `json:"confidence"`
	Size       CandleSize `json:"size"`
	Time       time.Time  `json:"time"`
}
type SignalConf struct {
	Name          string
	ResultantConf float64
}
