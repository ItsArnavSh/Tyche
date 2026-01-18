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

func (s *Server) BootServer(ctx context.Context) {
	var err error
	s.grpc, err = grpc.NewGrpcClient(s.server_addr)
	if err != nil {
		return
	}
	s.message_broker, err = messagebroker.NewRedisQueue("localhost:6379", "", 0, "signals")
	SignalChannel := make(chan entity.Signal)
	s.message_broker.Poll(ctx, SignalChannel)
}
