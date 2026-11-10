package genutil

import (
	"testing"

	"gateway/app/util/entity"
)

func TestBoundedHeapKeepsTopN(t *testing.T) {
	h := NewBoundedMaxHeap(3)
	for _, signal := range []entity.SignalConf{
		{Name: "A", ResultantConf: 0.2},
		{Name: "B", ResultantConf: 0.9},
		{Name: "C", ResultantConf: 0.5},
		{Name: "D", ResultantConf: 0.8},
		{Name: "E", ResultantConf: 0.1},
	} {
		h.Push(signal)
	}
	if h.Len() != 3 {
		t.Fatalf("len = %d", h.Len())
	}
	got := h.GetSorted()
	want := []string{"B", "D", "C"}
	for i := range want {
		if got[i].Name != want[i] {
			t.Fatalf("sorted[%d] = %s, want %s", i, got[i].Name, want[i])
		}
	}
}

func TestBoundedHeapTiesAndZero(t *testing.T) {
	h := NewBoundedMaxHeap(2)
	h.Push(entity.SignalConf{Name: "B", ResultantConf: 0.5})
	h.Push(entity.SignalConf{Name: "A", ResultantConf: 0.5})
	got := h.GetSorted()
	if got[0].Name != "A" || got[1].Name != "B" {
		t.Fatalf("tie order = %+v", got)
	}
	empty := NewBoundedMaxHeap(0)
	empty.Push(entity.SignalConf{Name: "X"})
	if empty.Len() != 0 {
		t.Fatal("zero-capacity heap should stay empty")
	}
}
