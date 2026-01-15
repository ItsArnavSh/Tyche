package entity

import "time"

const (
	SEC5 CandleSize = iota
	SEC30
	MIN1
	MIN15
	HOUR1
)

func (c CandleSize) GetMilliSize() float64 {
	switch c {
	case SEC5:
		return 5000
	case SEC30:
		return 30000
	case MIN1:
		return 60000
	case MIN15:
		return 900000
	case HOUR1:
		return 3600000
	}
	return 0
}

func (c CandleSize) GetExpiryDuration() time.Time {
	switch c {
	case SEC5:
		return time.Now().Add(time.Second * 5)
	case SEC30:
		return time.Now().Add(time.Second * 30)
	case MIN1:
		return time.Now().Add(time.Minute)
	case MIN15:
		return time.Now().Add(time.Minute * 15)
	case HOUR1:
		return time.Now().Add(time.Hour)
	}
	return time.Now()
}
