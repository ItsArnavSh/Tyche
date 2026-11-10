package entity

import "time"

type Signal struct {
	Name       string     `json:"name"`
	Confidence float64    `json:"confidence"`
	Size       CandleSize `json:"size"`
	Time       time.Time  `json:"time"`
	Strategy   string     `json:"strategy,omitempty"`
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
// the same strategy replace each other instead of stacking up.
func (s Signal) StrategyKey() string {
	if s.Strategy == "" {
		return "unknown"
	}
	return s.Strategy
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
