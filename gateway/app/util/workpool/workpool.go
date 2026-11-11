// Package workpool provides a small bounded-concurrency map helper.
package workpool

import "sync"

// Map applies fn to every item using at most workers goroutines.
//
// Results are returned in completion order. Callers that need deterministic
// output should sort the result. A worker count below one is treated as one,
// and no more workers than items are started.
func Map[T any, R any](items []T, workers int, fn func(T) R) []R {
	if len(items) == 0 {
		return nil
	}
	if workers < 1 {
		workers = 1
	}
	if workers > len(items) {
		workers = len(items)
	}

	jobs := make(chan T)
	results := make(chan R, len(items))
	var wg sync.WaitGroup

	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for item := range jobs {
				results <- fn(item)
			}
		}()
	}

	go func() {
		for _, item := range items {
			jobs <- item
		}
		close(jobs)
		wg.Wait()
		close(results)
	}()

	out := make([]R, 0, len(items))
	for result := range results {
		out = append(out, result)
	}
	return out
}
