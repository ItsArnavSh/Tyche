package grpc

import (
	"google.golang.org/grpc/resolver"
)

type manualResolverBuilder struct {
	scheme  string
	servers []string
}

func (m *manualResolverBuilder) Build(target resolver.Target, cc resolver.ClientConn, opts resolver.BuildOptions) (resolver.Resolver, error) {
	r := &manualResolver{
		target:  target,
		cc:      cc,
		servers: m.servers,
	}
	r.start()
	return r, nil
}

func (m *manualResolverBuilder) Scheme() string {
	return m.scheme
}

type manualResolver struct {
	target  resolver.Target
	cc      resolver.ClientConn
	servers []string
}

func (r *manualResolver) start() {
	var addrs []resolver.Address
	for _, server := range r.servers {
		addrs = append(addrs, resolver.Address{Addr: server})
	}
	r.cc.UpdateState(resolver.State{Addresses: addrs})
}

func (*manualResolver) ResolveNow(o resolver.ResolveNowOptions) {}
func (*manualResolver) Close()                                  {}
