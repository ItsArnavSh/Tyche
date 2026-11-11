package entity

import (
	"fmt"
	"time"
)

type Signal struct {
	Name       string     `json:"name"`
	Confidence float64    `json:"confidence"`
	Size       CandleSize `json:"size"`
	Time       time.Time  `json:"time"`
	Strategy   string     `json:"strategy,omitempty"`
	Family     string     `json:"family,omitempty"`
}

type SignalConf struct {
	Name string
	// ResultantConf is the decayed, capped aggregate used for ranking entries.
	ResultantConf float64
	// PeakConfidence is the strongest raw signal still live for the ticker.  It
	// is stored on the position so exits are not triggered by normal decay.
	PeakConfidence float64
}

// StrategyKey identifies the producer of a signal so repeated emissions from
// the same strategy and timeframe replace each other instead of stacking up.
// The timeframe is part of the key because the same strategy can legitimately
// emit independent signals on more than one candle size.
func (s Signal) StrategyKey() string {
	strategy := s.Strategy
	if strategy == "" {
		strategy = "unknown"
	}
	return fmt.Sprintf("%s@%s", strategy, s.Size.String())
}

// FamilyKey groups correlated strategies for aggregation.  Signals from older
// producers that do not send a family fall back to the strategy name.
func (s Signal) FamilyKey() string {
	if s.Family != "" {
		return s.Family
	}
	if s.Strategy != "" {
		return s.Strategy
	}
	return "unknown"
}

func (s Signal) ExpiresAt() time.Time {
	return s.Size.ExpiresAt(s.Time)
}

func (s Signal) Expired(now time.Time) bool {
	if s.Time.IsZero() {
		return false
	}
	return now.After(s.ExpiresAt())
}

func (s Signal) Clamped() Signal {
	if s.Confidence < 0 {
		s.Confidence = 0
	}
	if s.Confidence > 1 {
		s.Confidence = 1
	}
	return s
}
