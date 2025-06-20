package pyinterface

import (
	"context"
	"fmt"

	pythonpb "central/proto"

	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type PyClient struct {
	logger *zap.Logger
	client pythonpb.PythonUtilClient
	conn   *grpc.ClientConn
}

// NewPyClient creates and returns a new Python gRPC client
func NewPyClient(ctx context.Context, logger *zap.Logger) (*PyClient, error) {
	conn, err := grpc.NewClient(
		"passthrough:///localhost:50051",
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {

		return nil, fmt.Errorf("failed to dial Python gRPC server: %w", err)
	}
	logger.Info("Connected to GRPC Server")
	client := pythonpb.NewPythonUtilClient(conn)
	return &PyClient{
		logger: logger,
		client: client,
		conn:   conn,
	}, nil
}

// Close closes the gRPC connection
func (p *PyClient) Close() error {
	return p.conn.Close()
}

// CallParseNewsArticle sends a URL and returns parsed article
func (p *PyClient) CallParseNewsArticle(ctx context.Context, url string) (*pythonpb.ParseNewsArticleResponse, error) {
	resp, err := p.client.ParseNewsArticle(ctx, &pythonpb.ParseNewsArticleRequest{Url: url})
	if err != nil {
		p.logger.Error("ParseNewsArticle RPC failed", zap.Error(err))
		return nil, err
	}
	return resp, nil
}

// CallGenerateEmbeddings sends content and gets float embeddings
func (p *PyClient) CallGenerateEmbeddings(ctx context.Context, content string) (*pythonpb.GenerateEmbeddingsResponse, error) {
	resp, err := p.client.GenerateEmbeddings(ctx, &pythonpb.GenerateEmbeddingsRequest{Content: content})
	if err != nil {
		p.logger.Error("GenerateEmbeddings RPC failed", zap.Error(err))
		return nil, err
	}
	return resp, nil
}
