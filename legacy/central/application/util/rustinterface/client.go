package rsinterface

import (
	"central/application/util/entity"
	rustpb "central/proto"

	"go.uber.org/zap"
	"golang.org/x/net/context"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)
type RustClient struct{
	logger zap.Logger
	conn *grpc.ClientConn
	client rustpb.RustServiceClient
}

func NewRustClient(ctx context.Context,logger zap.Logger)(RustClient,error){
	conn,err := grpc.NewClient(
			"passthrough:///localhost:50052",
			grpc.WithTransportCredentials(insecure.NewCredentials()),
		)
	if err!=nil{
		return RustClient{},err
	}
	logger.Info("Connected to Rust Server")
	client := rustpb.NewRustServiceClient(conn)
	return RustClient{
		logger:logger,
		client: client,
		conn:conn,
	},nil
}
func (p *RustClient) Close() error {
	return p.conn.Close()
}

func (r *RustClient) SendToRust(ctx context.Context, stock <-chan entity.StockStream) {
	for row := range stock {
		var converted []*rustpb.StockValue
		for _, val := range row.StockValues {
			converted = append(converted, &rustpb.StockValue{
				Open:  val.Open,
				Close: val.Close,
				High:  val.High,
				Low:   val.Low,
				Name:  val.Ticker,
				// Volume is ignored since proto doesn't have it Todo
			})
		}

		_, err := r.client.SendStockData(ctx, &rustpb.SendStockDataRequest{
			Stocks: converted,
		})
		if err != nil {
			r.logger.Error("Error Sending Data to Rust", zap.Error(err))
		}
	}
}
