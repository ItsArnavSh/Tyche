package entity

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

type CandleSize int

const (
	SEC5 CandleSize = iota
	SEC30
	MIN1
	MIN15
	HOUR1
)

func (c CandleSize) Duration() time.Duration {
	switch c {
	case SEC5:
		return 5 * time.Second
	case SEC30:
		return 30 * time.Second
	case MIN1:
		return time.Minute
	case MIN15:
		return 15 * time.Minute
	case HOUR1:
		return time.Hour
	}
	return 0
}

// GetMilliSize is kept for compatibility with the original metrics code.
func (c CandleSize) GetMilliSize() float64 {
	return float64(c.Duration().Milliseconds())
}

// ExpiresAt returns the moment a signal of this candle size becomes stale.
func (c CandleSize) ExpiresAt(start time.Time) time.Time {
	if start.IsZero() {
		start = time.Now()
	}
	return start.Add(c.Duration())
}

// GetExpiryDuration is retained for backwards compatibility. It returns a
// timestamp relative to now; prefer ExpiresAt(start) when a signal timestamp
// is available.
func (c CandleSize) GetExpiryDuration() time.Time {
	return time.Now().Add(c.Duration())
}

func (c CandleSize) String() string {
	switch c {
	case SEC5:
		return "sec5"
	case SEC30:
		return "sec30"
	case MIN1:
		return "min1"
	case MIN15:
		return "min15"
	case HOUR1:
		return "hour1"
	}
	return "unknown"
}

func ParseCandleSize(value string) (CandleSize, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "sec5":
		return SEC5, nil
	case "sec30":
		return SEC30, nil
	case "min1":
		return MIN1, nil
	case "min15":
		return MIN15, nil
	case "hour1":
		return HOUR1, nil
	default:
		return SEC5, fmt.Errorf("unknown CandleSize: %s", value)
	}
}

func (c CandleSize) MarshalJSON() ([]byte, error) {
	return json.Marshal(c.String())
}

func (c *CandleSize) UnmarshalJSON(b []byte) error {
	var s string
	if err := json.Unmarshal(b, &s); err == nil {
		parsed, err := ParseCandleSize(s)
		if err != nil {
			return err
		}
		*c = parsed
		return nil
	}
	// Tolerate numeric values from older producers.
	var n int
	if err := json.Unmarshal(b, &n); err != nil {
		return fmt.Errorf("CandleSize must be a string or integer: %w", err)
	}
	if n < int(SEC5) || n > int(HOUR1) {
		return fmt.Errorf("CandleSize out of range: %d", n)
	}
	*c = CandleSize(n)
	return nil
}
