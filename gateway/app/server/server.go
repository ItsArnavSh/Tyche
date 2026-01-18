package server

import (
	"context"
	"fmt"
	"gateway/app/core/decision"
	"gateway/app/core/monitor"
	"gateway/app/interface/producer"
	"gateway/app/server/grpc"
	messagebroker "gateway/app/server/msgbroker"
	"gateway/app/util/bucket"
	"gateway/app/util/entity"
	"gateway/app/util/transaction"
	"log"
)

type Server struct {
	server_addr         []string
	grpc                *grpc.GrpcClient
	message_broker      *messagebroker.RedisQueue
	monitor             *monitor.TradeMonitor
	decision            decision.DecisionLayer
	transaction_handler transaction.TransactionHandler
	bucket              bucket.Bucket
	producer            producer.Producer
	sigchan             chan entity.Signal
}

func NewServer(ctx context.Context, addr []string) (*Server, error) {
	s := &Server{
		server_addr: addr,
		bucket:      bucket.NewBucket(),
	}
	var err error
	// gRPC client
	if s.grpc, err = grpc.NewGrpcClient(addr); err != nil {
		return nil, fmt.Errorf("init grpc client: %w", err)
	}
	// message broker
	if s.message_broker, err = messagebroker.NewRedisQueue(
		"localhost:6379",
		"",
		0,
		"signals",
	); err != nil {
		return nil, fmt.Errorf("init redis queue: %w", err)
	}
	// producer
	if s.producer, err = producer.NewProducer(); err != nil {
		return nil, fmt.Errorf("init producer: %w", err)
	}

	// transaction handler
	if s.transaction_handler, err = transaction.NewTransactionHandler(); err != nil {
		return nil, fmt.Errorf("init transaction handler: %w", err)
	}

	// monitor
	s.monitor = monitor.NewTradeMonitor(s.transaction_handler, s.bucket)

	// signals + decision layer
	s.sigchan = make(chan entity.Signal)
	s.decision = decision.NewDecisionLayer(
		s.transaction_handler,
		s.bucket,
		s.sigchan,
		s.monitor,
	)

	return s, nil
}
func goSafe(name string, fn func()) {
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("[panic] %s crashed: %v", name, r)
			}
		}()
		log.Printf("[start] %s", name)
		fn()
		log.Printf("[stop] %s exited", name)
	}()
}

func (s *Server) BootServer(ctx context.Context) error {
	goSafe("message-broker", func() { s.message_broker.Poll(ctx, s.sigchan) })
	goSafe("signal-poller", func() { s.decision.StartSignalPoller() })
	goSafe("decision-making", func() { s.decision.StartDecisionMaking(ctx, 5) })
	goSafe("monitor", func() { s.monitor.StartMonitor(ctx) })
	return nil
}
