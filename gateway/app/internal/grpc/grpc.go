package grpc

import (
	"fmt"
	pb "gateway/proto"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/resolver"
)

var (
	addrs = []string{":50051", ":50052"}
)

// Todo: Get all this from config instead of hardcoded
type GrpcClient struct {
	conn   *grpc.ClientConn
	client pb.RustServiceClient
}

func NewGrpcClient(servers []string) (*GrpcClient, error) {
	// Build the target with multiple addresses
	target := buildTarget(servers)

	// Set up connection with round robin
	conn, err := grpc.NewClient(
		target,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithDefaultServiceConfig(`{"loadBalancingPolicy":"round_robin"}`),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to connect: %v", err)
	}

	return &GrpcClient{
		conn:   conn,
		client: pb.NewRustServiceClient(conn),
	}, nil
}
func buildTarget(servers []string) string {
	// Register a manual resolver
	resolver.Register(&manualResolverBuilder{
		scheme:  "multi",
		servers: servers,
	})

	return "multi:///servers"
}

func (c *GrpcClient) Close() error {
	return c.conn.Close()
}
