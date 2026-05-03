package org.Tyche.src.internal.grpc;

import java.util.ArrayList;

import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.CoreAPI;
import org.Tyche.src.core.producer.StockInterface;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.server.Server;

import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import stockrec.v1.RustServiceGrpc;
import stockrec.v1.Stockrec.*;

public class GRPC {

    StockInterface stockapi;

    public GRPC(StockInterface stockapi) {
        this.stockapi = stockapi;
    }

    public static void start(Server server) throws Exception {
        io.grpc.Server grpcServer = ServerBuilder
                .forPort(50051)
                .addService(new RustServiceImpl(server))
                .maxInboundMessageSize(50 * 1024 * 1024)
                .build()
                .start();
        System.out.println("gRPC server started on port 50051");
        grpcServer.awaitTermination();
    }

    static class RustServiceImpl extends RustServiceGrpc.RustServiceImplBase {
        private final Server server;

        RustServiceImpl(Server server) {
            this.server = server;
        }

        @Override
        public void sendRollData(SendRollDataRequest request,
                StreamObserver<SendRollDataResponse> responseObserver) {
            try {
                System.out.println("Was Just Called");
                RollRequest rreq = new RollRequest();

                for (var stock : request.getStockList()) {
                    CoreAPI.StockUpdate su = new CoreAPI.StockUpdate(stock.getName());

                    for (var ticker : stock.getValsList()) {
                        var size = CandleSize.ConvToEntity(ticker.getSize());
                        var val = ticker.getVal();

                        Candle candle = new Candle(val.getOpen(), val.getHigh(), val.getLow(), val.getClose(), 0);
                        su.val.add(new CoreAPI.LatestStockVal(candle, size));
                    }

                    rreq.update.add(su);
                }

                // now rreq is fully built, pass to your engine
                var missing = server.stockapi.PushLatestVals(rreq);
                ArrayList<StockSeries> ser = new ArrayList<>();
                for (var mis : missing) {
                    StockSeries stock_info = StockSeries.newBuilder().setName(mis.name)
                            .setSize(CandleSize.ConvToProto(mis.size)).build();
                    ser.add(stock_info);
                }
                SendRollDataResponse response = SendRollDataResponse.newBuilder()
                        .addAllMissing(ser)
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception e) {
                System.err.println(e);
            }
        }

        @Override
        public void sendBootSignal(SendBootSignalRequest request,
                StreamObserver<SendBootSignalResponse> responseObserver) {
            try {
                CoreAPI.BootRequest breq = new CoreAPI.BootRequest();

                for (var hist : request.getHistList()) {
                    CoreAPI.TickerHistory th = new CoreAPI.TickerHistory(hist.getName());

                    for (var series : hist.getSeriesList()) {
                        CoreAPI.Series s = new CoreAPI.Series(CandleSize.ConvToEntity(series.getSize()));

                        for (var val : series.getValList()) {
                            Candle candle = new Candle(val.getOpen(), val.getHigh(), val.getLow(), val.getClose(), 0);
                            s.candles.add(candle);
                        }

                        th.series.add(s);
                    }

                    breq.history.add(th);
                }

                // now breq is fully built
                server.stockapi.PushHistoricalVals(breq);
                responseObserver.onNext(SendBootSignalResponse.newBuilder().build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                System.err.println(e);
            }
        }

        @Override
        public void sendKillSignal(SendKillSignalRequest request,
                StreamObserver<SendKillSignalResponse> responseObserver) {
            // request.getNameList() — list of strings
            responseObserver.onNext(SendKillSignalResponse.newBuilder().build());
            responseObserver.onCompleted();
        }
    }
}