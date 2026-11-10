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

// NewGrpcClient dials the Java core with bounded retries so the gateway can
// start before the core without relying on Docker Compose health ordering.
func NewGrpcClient(ctx context.Context, addr string) (*GrpcClient, error) {
	log.Printf("[grpc] connecting to %s...", addr)
	deadline := time.Now().Add(60 * time.Second)
	var lastErr error
	for attempt := 1; ; attempt++ {
		attemptCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
		conn, err := grpc.DialContext(
			attemptCtx,
			addr,
			grpc.WithTransportCredentials(insecure.NewCredentials()),
			grpc.WithBlock(),
			grpc.WithDefaultCallOptions(
				grpc.MaxCallRecvMsgSize(1024*1024*50),
				grpc.MaxCallSendMsgSize(1024*1024*50),
			),
		)
		cancel()
		if err == nil {
			log.Printf("[grpc] connected to %s", addr)
			return &GrpcClient{client: pb.NewRustServiceClient(conn), conn: conn}, nil
		}
		lastErr = err
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("failed to connect to gRPC server %s after %d attempts: %w", addr, attempt, lastErr)
		}
		log.Printf("[grpc] connection attempt %d to %s failed: %v; retrying", attempt, addr, err)
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(time.Second):
		}
	}
}

func (g *GrpcClient) Client() pb.RustServiceClient {
	return g.client
}

func (g *GrpcClient) Close() error {
	if g.conn == nil {
		return nil
	}
	return g.conn.Close()
}
