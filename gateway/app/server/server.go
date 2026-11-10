package server

import (
	"context"
	"fmt"
	"log"
	"runtime"
	"sync"
	"time"

	"gateway/app/config"
	"gateway/app/core/decision"
	"gateway/app/core/monitor"
	"gateway/app/interface/producer"
	"gateway/app/internal/grpc"
	messagebroker "gateway/app/internal/msgbroker"
	"gateway/app/util/bucket"
	"gateway/app/util/entity"
	"gateway/app/util/transaction"
)

type Server struct {
	cfg         config.Config
	grpc        *grpc.GrpcClient
	broker      *messagebroker.RedisQueue
	monitor     *monitor.TradeMonitor
	decision    *decision.DecisionLayer
	transaction transaction.TransactionHandler
	bucket      *bucket.Bucket
	producer    producer.Producer
	signals     chan entity.Signal
	tradeIface  *producer.TradeInterface
}

func NewServer(ctx context.Context, cfg config.Config) (*Server, error) {
	s := &Server{cfg: cfg}

	var err error
	if s.grpc, err = grpc.NewGrpcClient(ctx, cfg.CoreAddr); err != nil {
		return nil, fmt.Errorf("init grpc client: %w", err)
	}
	if s.broker, err = messagebroker.NewRedisQueue(
		cfg.RedisAddr,
		cfg.RedisPassword,
		cfg.RedisDB,
		cfg.SignalQueue,
	); err != nil {
		s.grpc.Close()
		return nil, fmt.Errorf("init redis queue: %w", err)
	}
	if s.producer, err = producer.NewProducer(cfg); err != nil {
		s.broker.Close()
		s.grpc.Close()
		return nil, fmt.Errorf("init producer: %w", err)
	}
	if s.transaction, err = transaction.NewTransactionHandler(cfg.InitialCash, cfg.PaperFeeBPS); err != nil {
		s.producer.Close()
		s.broker.Close()
		s.grpc.Close()
		return nil, fmt.Errorf("init transaction handler: %w", err)
	}

	s.bucket = bucket.NewBucket(s.producer)
	s.monitor = monitor.NewTradeMonitorWithInterval(s.transaction, s.bucket, cfg.MonitorEvery)
	s.signals = make(chan entity.Signal, 1024)
	tradeInterface := producer.NewTradeInterface(s.grpc, s.producer)
	s.decision = decision.NewDecisionLayer(
		s.transaction,
		s.bucket,
		s.signals,
		s.monitor,
		decision.Options{Settings: cfg.Decision, Interval: cfg.DecisionEvery},
	)
	s.tradeIface = tradeInterface

	log.Printf("[server] data source=%s symbols=%d cash=%.2f core=%s redis=%s",
		cfg.DataSource, len(cfg.Symbols), cfg.InitialCash, cfg.CoreAddr, cfg.RedisAddr)
	return s, nil
}

func (s *Server) BootServer(ctx context.Context) error {
	var wg sync.WaitGroup
	start := func(name string, fn func()) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() {
				if r := recover(); r != nil {
					buf := make([]byte, 8192)
					n := runtime.Stack(buf, false)
					log.Printf("[panic] %s crashed: %v\n%s", name, r, buf[:n])
				}
			}()
			log.Printf("[start] %s", name)
			started := time.Now()
			fn()
			log.Printf("[stop] %s exited after %s", name, time.Since(started).Round(time.Millisecond))
		}()
	}

	start("message-broker", func() { s.broker.Poll(ctx, s.signals) })
	start("signal-poller", func() { s.decision.StartSignalPoller(ctx) })
	start("decision-making", func() { s.decision.StartDecisionMaking(ctx) })
	start("monitor", func() { s.monitor.StartMonitor(ctx) })
	start("trade-interface", func() { s.tradeIface.IllDoTheTalking(ctx) })

	<-ctx.Done()
	log.Println("[server] shutdown requested")

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(s.cfg.ShutdownWait):
		log.Println("[server] shutdown grace period elapsed; closing dependencies")
	}

	s.monitor.PrintSummary()
	if err := s.producer.Close(); err != nil {
		log.Printf("[server] producer close: %v", err)
	}
	if err := s.broker.Close(); err != nil {
		log.Printf("[server] redis close: %v", err)
	}
	if err := s.grpc.Close(); err != nil {
		log.Printf("[server] grpc close: %v", err)
	}
	return nil
}
