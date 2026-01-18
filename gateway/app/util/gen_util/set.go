package genutil

type Set[T comparable] map[T]struct{}

func NewSet[T comparable]() Set[T] {
	return make(Set[T])
}

func (s Set[T]) Add(item T) {
	s[item] = struct{}{}
}

func (s Set[T]) Has(item T) bool {
	_, exists := s[item]
	return exists
}

func (s Set[T]) Remove(item T) {
	delete(s, item)
}

func (s Set[T]) Size() int {
	return len(s)
}

func (s Set[T]) Clear() {
	for k := range s {
		delete(s, k)
	}
}

func (s Set[T]) ListAll() []T {
	var list []T
	for k := range s {
		list = append(list, k)
	}
	return list
}
