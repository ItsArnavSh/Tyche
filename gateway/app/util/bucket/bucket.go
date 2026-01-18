package bucket

import (
	"fmt"
	"gateway/app/interface/producer"
	"gateway/app/util/entity"
	util "gateway/app/util/gen_util"
	"time"
)

// Each stock has its own DecayMap
// Each DecayMap contains a list of all the active signals
// On running GetConfidence, we scan through all the active signals
// And also remove the irrelevant ones, and basically plot the value on an exponential decay curve
// It becomes irrelevant when it is past the ticker size expiry date
//
//

type Bucket struct {
	DecayMap map[string]util.Set[entity.Signal]
	Producer producer.Producer
}

func NewBucket() Bucket {
	return Bucket{
		DecayMap: make(map[string]util.Set[entity.Signal]),
	}
}
func (b *Bucket) GetConfidence(name string) (float64, error) {
	sol := b.DecayMap[name]
	if sol == nil {
		return 0, fmt.Errorf("Stock not found in bucket")
	}
	return b.convertToConfidence(name), nil
}
func (b *Bucket) convertToConfidence(name string) float64 {
	m, exists := b.DecayMap[name]
	if !exists {
		return 0.0
	}

	var totalConfidence float64 = 0.0
	now := time.Now()

	var toRemove []entity.Signal

	for signal := range m {
		if signal.Size.GetExpiryDuration().Before(now) {
			toRemove = append(toRemove, signal)
			continue
		}

		// Calculate decayed confidence
		decayed := util.ExponentialDecay(
			signal.Confidence,                   // starting value
			now,                                 // current time
			signal.Time,                         // start time of this signal
			float64(signal.Size.GetMilliSize()), // total decay duration in ms
		)

		totalConfidence += decayed
	}

	// Clean up expired signals **after** iteration
	for _, sig := range toRemove {
		m.Remove(sig)
	}

	return totalConfidence
}
func (b *Bucket) SignalBucket(signal entity.Signal) {
	if b.DecayMap[signal.Name] == nil {
		b.DecayMap[signal.Name] = util.NewSet[entity.Signal]()
	}
	b.DecayMap[signal.Name].Add(signal)
}

func (b *Bucket) GetTopNinBudget(N int, budget float64) []entity.SignalConf {
	pq := util.NewBoundedMaxHeap(N)
	for key, _ := range b.DecayMap {
		if budget >= b.Producer.GetCurrentValue(key) {
			pq.Push(entity.SignalConf{Name: key, ResultantConf: b.convertToConfidence(key)})
		}
	}
	return pq.GetSorted()
}
