package workpool

import (
	"sort"
	"sync/atomic"
	"testing"
)

func TestMapBoundsWorkersAndReturnsEveryResult(t *testing.T) {
	items := []int{1, 2, 3, 4, 5, 6, 7}
	var inFlight atomic.Int32
	var maxInFlight atomic.Int32

	results := Map(items, 3, func(item int) int {
		current := inFlight.Add(1)
		for {
			observed := maxInFlight.Load()
			if current <= observed || maxInFlight.CompareAndSwap(observed, current) {
				break
			}
		}
		inFlight.Add(-1)
		return item * 2
	})

	sort.Ints(results)
	want := []int{2, 4, 6, 8, 10, 12, 14}
	if len(results) != len(want) {
		t.Fatalf("results = %v", results)
	}
	for i := range want {
		if results[i] != want[i] {
			t.Fatalf("results = %v, want %v", results, want)
		}
	}
	if maxInFlight.Load() > 3 {
		t.Fatalf("max in flight = %d", maxInFlight.Load())
	}
}

func TestMapEmptyAndZeroWorkers(t *testing.T) {
	if got := Map([]int(nil), 0, func(int) int { return 0 }); got != nil {
		t.Fatalf("empty result = %v", got)
	}
	got := Map([]int{1, 2}, 0, func(item int) int { return item })
	if len(got) != 2 {
		t.Fatalf("result = %v", got)
	}
}
