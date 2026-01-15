package util

import (
	"math"
	"time"
)

func ExponentialDecay(
	startValue float64,
	currentTime time.Time,
	startTime time.Time,
	totalDurationMs float64,
) float64 {

	elapsedMs := float64(currentTime.Sub(startTime).Milliseconds())
	if elapsedMs <= 0 {
		return startValue
	}
	if elapsedMs >= totalDurationMs {
		return startValue * 0.01
	}

	// Core formula: decays to 1% after totalDurationMs
	ratio := elapsedMs / totalDurationMs
	decayFactor := math.Pow(0.01, ratio)

	return startValue * decayFactor
}

func ExpDecayTo1Percent(start float64, elapsedMs, totalDurationMs float64) float64 {
	if elapsedMs <= 0 {
		return start
	}
	if elapsedMs >= totalDurationMs {
		return start * 0.01
	}
	return start * math.Pow(0.01, elapsedMs/totalDurationMs)
}
