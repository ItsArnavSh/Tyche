package genutil

import (
	"gateway/app/util/entity"
	"math/rand"
)

func CandleGen(currPrice float64) entity.CandleData {
	volatility := 0.02
	change := (rand.Float64() - 0.5) * 2 * volatility * currPrice

	open := currPrice
	close := currPrice + change

	high := max(open, close) * (1 + rand.Float64()*0.01)
	low := min(open, close) * (1 - rand.Float64()*0.01)

	volume := rand.Int63n(1_000_000) + 100_000

	return entity.CandleData{
		Open:   open,
		High:   high,
		Low:    low,
		Close:  close,
		Volume: volume,
	}
}

func max(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}

func min(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}
