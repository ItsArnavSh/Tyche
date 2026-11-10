package genutil

import (
	"container/heap"
	"sort"

	"gateway/app/util/entity"
)

// signalMinHeap keeps the *smallest* signal at the root, which makes it cheap
// to evict the weakest candidate when maintaining a top-N set.
type signalMinHeap []entity.SignalConf

func (h signalMinHeap) Len() int           { return len(h) }
func (h signalMinHeap) Less(i, j int) bool { return h[i].ResultantConf < h[j].ResultantConf }
func (h signalMinHeap) Swap(i, j int)      { h[i], h[j] = h[j], h[i] }

func (h *signalMinHeap) Push(x any) {
	*h = append(*h, x.(entity.SignalConf))
}

func (h *signalMinHeap) Pop() any {
	old := *h
	n := len(old)
	item := old[n-1]
	*h = old[:n-1]
	return item
}

// BoundedMaxHeap tracks the N highest-confidence signals.
type BoundedMaxHeap struct {
	heap    *signalMinHeap
	maxSize int
}

func NewBoundedMaxHeap(maxSize int) *BoundedMaxHeap {
	if maxSize < 0 {
		maxSize = 0
	}
	h := &signalMinHeap{}
	heap.Init(h)
	return &BoundedMaxHeap{heap: h, maxSize: maxSize}
}

func (b *BoundedMaxHeap) Push(x entity.SignalConf) {
	if b.maxSize == 0 {
		return
	}
	if b.heap.Len() < b.maxSize {
		heap.Push(b.heap, x)
		return
	}
	// Replace the weakest kept element when the candidate is stronger.
	if x.ResultantConf > (*b.heap)[0].ResultantConf {
		heap.Pop(b.heap)
		heap.Push(b.heap, x)
	}
}

func (b *BoundedMaxHeap) Pop() entity.SignalConf {
	return heap.Pop(b.heap).(entity.SignalConf)
}

func (b *BoundedMaxHeap) Peek() entity.SignalConf {
	return (*b.heap)[0]
}

func (b *BoundedMaxHeap) Len() int {
	return b.heap.Len()
}

// GetAll returns the retained candidates in unspecified order.
func (b *BoundedMaxHeap) GetAll() []entity.SignalConf {
	out := make([]entity.SignalConf, len(*b.heap))
	copy(out, *b.heap)
	return out
}

// GetSorted returns retained candidates from strongest to weakest.
func (b *BoundedMaxHeap) GetSorted() []entity.SignalConf {
	out := b.GetAll()
	sort.Slice(out, func(i, j int) bool {
		if out[i].ResultantConf == out[j].ResultantConf {
			return out[i].Name < out[j].Name
		}
		return out[i].ResultantConf > out[j].ResultantConf
	})
	return out
}
