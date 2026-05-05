package grpc

import (
	"context"
	"fmt"
	pb "gateway/proto"
	"log"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type GrpcClient struct {
	client pb.RustServiceClient
	conn   *grpc.ClientConn
}

func NewGrpcClient(addr string) (*GrpcClient, error) {
	log.Printf("[grpc] connecting to %s...", addr)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	conn, err := grpc.DialContext(
		ctx,
		addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(), // 🔥 THIS is key
		grpc.WithDefaultCallOptions(
			grpc.MaxCallRecvMsgSize(1024*1024*50),
			grpc.MaxCallSendMsgSize(1024*1024*50),
		),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to gRPC server: %w", err)
	}

	log.Printf("[grpc] connected to %s", addr)

	return &GrpcClient{
		client: pb.NewRustServiceClient(conn),
		conn:   conn,
	}, nil
}
func (g *GrpcClient) Client() pb.RustServiceClient {
	return g.client
}

func (g *GrpcClient) Close() error {
	return g.conn.Close()
}

func min(a, b time.Duration) time.Duration {
	if a < b {
		return a
	}
	return b
}
