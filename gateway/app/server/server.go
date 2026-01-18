package server

import (
	"context"
	"gateway/app/server/grpc"
	messagebroker "gateway/app/server/msgbroker"
	"gateway/app/util/entity"
)

type Server struct {
	server_addr    []string
	grpc           *grpc.GrpcClient
	message_broker *messagebroker.RedisQueue
}

func NewServer(ctx context.Context, addr []string) (*Server, error) {
	s := &Server{}
	s.server_addr = addr
	var err error
	s.grpc, err = grpc.NewGrpcClient(s.server_addr)
	if err != nil {
		return nil, err
	}
	s.message_broker, err = messagebroker.NewRedisQueue("localhost:6379", "", 0, "signals")
	return s, nil
}
func (s *Server) BootServer(ctx context.Context) {
	SignalChannel := make(chan entity.Signal)
	go s.message_broker.Poll(ctx, SignalChannel)

}
