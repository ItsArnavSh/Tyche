package entity

import "time"

type Signal struct {
	Name       string
	Confidence float64
	Size       CandleSize
	Time       time.Time
}
