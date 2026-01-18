package genutil

import (
	"container/heap"
	"gateway/app/util/entity"
)

// MaxHeap implements heap.Interface for a max heap of SignalConf
type MaxHeap []entity.SignalConf

func (h MaxHeap) Len() int           { return len(h) }
func (h MaxHeap) Less(i, j int) bool { return h[i].ResultantConf > h[j].ResultantConf } // > for max heap
func (h MaxHeap) Swap(i, j int)      { h[i], h[j] = h[j], h[i] }

func (h *MaxHeap) Push(x any) {
	*h = append(*h, x.(entity.SignalConf))
}

func (h *MaxHeap) Pop() any {
	old := *h
	n := len(old)
	x := old[n-1]
	*h = old[0 : n-1]
	return x
}

// Peek returns the maximum element without removing it
func (h MaxHeap) Peek() entity.SignalConf {
	return h[0]
}

// BoundedMaxHeap is a max heap with a size limit to track N smallest elements
type BoundedMaxHeap struct {
	heap    *MaxHeap
	maxSize int
}

// NewBoundedMaxHeap creates a new bounded max heap with the specified max size
func NewBoundedMaxHeap(maxSize int) *BoundedMaxHeap {
	h := &MaxHeap{}
	heap.Init(h)
	return &BoundedMaxHeap{
		heap:    h,
		maxSize: maxSize,
	}
}

// Push adds an element to the heap, maintaining the size constraint
func (b *BoundedMaxHeap) Push(x entity.SignalConf) {
	if b.heap.Len() < b.maxSize {
		// Heap not full yet, just add
		heap.Push(b.heap, x)
	} else if x.ResultantConf < b.heap.Peek().ResultantConf {
		// New element is smaller than the largest (top of max heap)
		// Replace the largest with the new smaller element
		heap.Pop(b.heap)
		heap.Push(b.heap, x)
	}
	// If x is larger than the largest in heap, ignore it
}

// Pop removes and returns the maximum element
func (b *BoundedMaxHeap) Pop() entity.SignalConf {
	return heap.Pop(b.heap).(entity.SignalConf)
}

// Peek returns the maximum element without removing it
func (b *BoundedMaxHeap) Peek() entity.SignalConf {
	return b.heap.Peek()
}

// Len returns the current size of the heap
func (b *BoundedMaxHeap) Len() int {
	return b.heap.Len()
}

// GetAll returns all elements in the heap (not in sorted order)
func (b *BoundedMaxHeap) GetAll() []entity.SignalConf {
	return *b.heap
}

// GetSorted returns all elements sorted from smallest to largest
func (b *BoundedMaxHeap) GetSorted() []entity.SignalConf {
	result := make([]entity.SignalConf, b.heap.Len())
	// Pop all elements (they come out largest to smallest)
	for i := len(result) - 1; i >= 0; i-- {
		result[i] = heap.Pop(b.heap).(entity.SignalConf)
	}
	// Restore the heap
	for _, val := range result {
		heap.Push(b.heap, val)
	}
	return result
}
